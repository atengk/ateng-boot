package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.observability.autoconfigure.AtengHealthAutoConfiguration;
import io.github.atengk.boot.observability.autoconfigure.AtengObservabilityAutoConfiguration;
import io.github.atengk.boot.observability.event.HealthProbeDegradedEvent;
import io.github.atengk.boot.observability.event.HealthProbeRecoveredEvent;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AbstractSafeHealthIndicator 防假死基类单元测试
 * 验证正常 UP、延迟超时切断降级 UNKNOWN、异常抛出降级 DOWN 及保底防御
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AbstractSafeHealthIndicator 防假死基类单元测试")
class AbstractSafeHealthIndicatorTest {

    @AfterEach
    void tearDownContext() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("正常探测：子类正常返回并打标，返回 UP")
    void testNormalProbeUp() {
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofSeconds(1)) {
            @Override
            protected void doCheck(Health.Builder builder) {
                builder.up().withDetail("component", "mock-subsystem").withDetail("latency", "1ms");
            }
        };

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("component", "mock-subsystem");
        assertThat(health.getDetails()).containsEntry("latency", "1ms");
    }

    @Test
    @DisplayName("显式降级探测：子类显式声明 UNKNOWN 状态，原样保留并返回")
    void testProbeExplicitUnknown() {
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofSeconds(1)) {
            @Override
            protected void doCheck(Health.Builder builder) {
                builder.unknown().withDetail("info", "explicit unknown");
            }
        };

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("info", "explicit unknown");
    }

    @Test
    @DisplayName("超时熔断：子类执行耗时超出阈值（100ms 阈值注入 500ms 延迟），立即切断降级返回 UNKNOWN")
    void testProbeTimeoutDegradation() {
        Duration timeout = Duration.ofMillis(100);
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(timeout) {
            @Override
            protected void doCheck(Health.Builder builder) throws Exception {
                Thread.sleep(500);
                builder.up();
            }
        };

        long start = System.currentTimeMillis();
        Health health = indicator.health();
        long elapsed = System.currentTimeMillis() - start;

        // 验证快速切断（远小于 500ms，在 100ms~300ms 之间切断）
        assertThat(elapsed).isLessThan(450);
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails())
                .containsEntry("status", "TIMEOUT")
                .containsEntry("timeout", timeout.toString())
                .containsEntry("reason", "Probe execution timed out");
    }

    @Test
    @DisplayName("异常阻断：子类探测发生不可逆异常，转译返回 DOWN 并保留因果原因")
    void testProbeExceptionDown() {
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofSeconds(1)) {
            @Override
            protected void doCheck(Health.Builder builder) {
                throw new IllegalStateException("磁盘 IO 阻塞故障");
            }
        };

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "磁盘 IO 阻塞故障");
    }

    @Test
    @DisplayName("配置属性解析与自定义线程池构造验证")
    void testPropertiesAndCustomExecutor() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getHealth().setTimeout(Duration.ofMillis(300));
        Executor executor = Executors.newSingleThreadExecutor();

        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(properties, executor) {
            @Override
            protected void doCheck(Health.Builder builder) {
                builder.up().withDetail("custom", true);
            }
        };

        assertThat(indicator.getTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(indicator.getExecutor()).isSameAs(executor);

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("custom", true);
    }

    @Test
    @DisplayName("领域事件发布：超时切断触发 HealthProbeDegradedEvent 发布")
    void testDegradedEventOnTimeout() {
        List<ApplicationEvent> events = new ArrayList<>();
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofMillis(80)) {
            @Override
            protected String getComponentName() {
                return "test-component";
            }

            @Override
            protected void doCheck(Health.Builder builder) throws Exception {
                Thread.sleep(400);
                builder.up();
            }
        };
        indicator.setApplicationEventPublisher(event -> {
            if (event instanceof ApplicationEvent appEvent) {
                events.add(appEvent);
            }
        });

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(HealthProbeDegradedEvent.class);

        HealthProbeDegradedEvent event = (HealthProbeDegradedEvent) events.get(0);
        assertThat(event.getComponentName()).isEqualTo("test-component");
        assertThat(event.getStatus()).isEqualTo("TIMEOUT");
        assertThat(event.getCostMs()).isGreaterThanOrEqualTo(80);
        assertThat(event.getReason()).contains("timed out");
    }

    @Test
    @DisplayName("领域事件发布：发生不可逆异常触发 HealthProbeDegradedEvent 发布")
    void testDegradedEventOnException() {
        List<ApplicationEvent> events = new ArrayList<>();
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofSeconds(1)) {
            @Override
            protected String getComponentName() {
                return "database";
            }

            @Override
            protected void doCheck(Health.Builder builder) {
                throw new IllegalStateException("连接池耗尽");
            }
        };
        indicator.setApplicationEventPublisher(event -> {
            if (event instanceof ApplicationEvent appEvent) {
                events.add(appEvent);
            }
        });

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(HealthProbeDegradedEvent.class);

        HealthProbeDegradedEvent event = (HealthProbeDegradedEvent) events.get(0);
        assertThat(event.getComponentName()).isEqualTo("database");
        assertThat(event.getStatus()).isEqualTo("DOWN");
        assertThat(event.getReason()).isEqualTo("连接池耗尽");
        assertThat(event.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("领域事件发布：正常 UP 状态不发布降级事件")
    void testNoDegradedEventOnUp() {
        List<ApplicationEvent> events = new ArrayList<>();
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofSeconds(1)) {
            @Override
            protected void doCheck(Health.Builder builder) {
                builder.up();
            }
        };
        indicator.setApplicationEventPublisher(event -> {
            if (event instanceof ApplicationEvent appEvent) {
                events.add(appEvent);
            }
        });

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("BeanFactoryAware 自动绑定：Spring 托管子类未传 Executor 时自动绑定 atengHealthProbeExecutor")
    void testBeanFactoryAutoBindsManagedProbeExecutor() {
        AtomicReference<String> probeThreadName = new AtomicReference<>();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengObservabilityAutoConfiguration.class,
                        AtengHealthAutoConfiguration.class
                ))
                .withBean("managedProbe", AbstractSafeHealthIndicator.class, () -> {
                    ObservabilityProperties props = new ObservabilityProperties();
                    return new AbstractSafeHealthIndicator(props) {
                        @Override
                        protected void doCheck(Health.Builder builder) {
                            probeThreadName.set(Thread.currentThread().getName());
                            builder.up();
                        }
                    };
                })
                .run(context -> {
                    AbstractSafeHealthIndicator probe = context.getBean("managedProbe", AbstractSafeHealthIndicator.class);
                    Executor expectedExecutor = context.getBean(AtengHealthAutoConfiguration.HEALTH_PROBE_EXECUTOR_BEAN_NAME, Executor.class);

                    assertThat(probe.getExecutor()).isSameAs(expectedExecutor);
                    assertThat(probe.getExecutor()).isNotSameAs(ForkJoinPool.commonPool());

                    Health health = probe.health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(probeThreadName.get()).startsWith("ateng-health-probe-");
                });
    }

    @Test
    @DisplayName("异步探测四维 AsyncContext 透传与对称清理：正常及异常/超时后工作线程现场零污染")
    void testAsyncContextPropagationAndCleanup() throws Exception {
        ThreadPoolTaskExecutor singlePool = new ThreadPoolTaskExecutor();
        singlePool.setCorePoolSize(1);
        singlePool.setMaxPoolSize(1);
        singlePool.setThreadNamePrefix("probe-ctx-worker-");
        singlePool.initialize();

        try {
            AtomicReference<String> capturedSnapshot = new AtomicReference<>();
            AbstractSafeHealthIndicator upProbe = new AbstractSafeHealthIndicator(Duration.ofSeconds(1), singlePool) {
                @Override
                protected void doCheck(Health.Builder builder) {
                    capturedSnapshot.set(
                            MDC.get("traceId") + "|"
                                    + UserContextHolder.get() + "|"
                                    + TenantContextHolder.getTenantId() + "|"
                                    + TenantContextHolder.isIgnoreTenant()
                    );
                    builder.up();
                }
            };

            MDC.put("traceId", "probe-trace-998");
            UserContextHolder.set("probe-user-998");
            TenantContextHolder.setTenantId("probe-tenant-998");
            TenantContextHolder.runWithoutTenant(() -> {
                Health health = upProbe.health();
                assertThat(health.getStatus()).isEqualTo(Status.UP);
            });

            assertThat(capturedSnapshot.get()).isEqualTo("probe-trace-998|probe-user-998|probe-tenant-998|true");

            // 验证异常及超时分支下工作线程现场同样被 finally 确定性清理还原
            AbstractSafeHealthIndicator timeoutProbe = new AbstractSafeHealthIndicator(Duration.ofMillis(60), singlePool) {
                @Override
                protected void doCheck(Health.Builder builder) throws Exception {
                    Thread.sleep(200);
                    builder.up();
                }
            };
            Health timeoutHealth = timeoutProbe.health();
            assertThat(timeoutHealth.getStatus()).isEqualTo(Status.UNKNOWN);

            // 提交干净任务检验唯一工作线程是否存在上下文残留污染
            String workerResidual = CompletableFuture.supplyAsync(() ->
                    MDC.get("traceId") + "|"
                            + UserContextHolder.get() + "|"
                            + TenantContextHolder.getTenantId() + "|"
                            + TenantContextHolder.isIgnoreTenant(), singlePool
            ).get(2, TimeUnit.SECONDS);

            assertThat(workerResidual).isEqualTo("null|null|null|false");
        } finally {
            singlePool.destroy();
        }
    }

    @Test
    @DisplayName("对称自愈闭环：UP -> DOWN/TIMEOUT -> UP 状态机精确发布 HealthProbeRecoveredEvent 并支持 resetProbeState")
    void testDegradedAndRecoveredLifecycleWithStateMachine() {
        List<ApplicationEvent> events = new ArrayList<>();
        // phase: 0=UP, 1=DOWN(异常), 2=TIMEOUT, 3=UP
        AtomicInteger phase = new AtomicInteger(0);

        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofMillis(60)) {
            @Override
            protected String getComponentName() {
                return "paymentGateway";
            }

            @Override
            protected void doCheck(Health.Builder builder) throws Exception {
                switch (phase.get()) {
                    case 1 -> builder.down().withDetail("err", "down");
                    case 2 -> {
                        Thread.sleep(200);
                        builder.up();
                    }
                    default -> builder.up();
                }
            }
        };
        indicator.setApplicationEventPublisher(e -> {
            if (e instanceof ApplicationEvent appEvent) {
                events.add(appEvent);
            }
        });

        // 1. 初次 UP -> 不误发任何升降级事件
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(events).isEmpty();

        // 2. 连续 3 次故障（DOWN, DOWN, TIMEOUT）
        phase.set(1);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        phase.set(2);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);

        long degradedCount = events.stream().filter(HealthProbeDegradedEvent.class::isInstance).count();
        assertThat(degradedCount).isEqualTo(3);

        // 3. 恢复为 UP -> 发布对称的 HealthProbeRecoveredEvent
        phase.set(3);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        List<HealthProbeRecoveredEvent> recoveredList = events.stream()
                .filter(HealthProbeRecoveredEvent.class::isInstance)
                .map(HealthProbeRecoveredEvent.class::cast)
                .toList();
        assertThat(recoveredList).hasSize(1);
        HealthProbeRecoveredEvent recovered = recoveredList.get(0);
        assertThat(recovered.getComponentName()).isEqualTo("paymentGateway");
        assertThat(recovered.getPreviousStatus()).isEqualTo("TIMEOUT");
        assertThat(recovered.getConsecutiveFailures()).isEqualTo(3);
        assertThat(recovered.getDowntimeMs()).isGreaterThanOrEqualTo(0L);

        // 4. 持续 UP 不重复发恢复事件
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(events.stream().filter(HealthProbeRecoveredEvent.class::isInstance).count()).isEqualTo(1);

        // 5. resetProbeState 复位后从故障切回 UP 不触发恢复事件
        events.clear();
        phase.set(1);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        indicator.resetProbeState();
        phase.set(0);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(events.stream().filter(HealthProbeRecoveredEvent.class::isInstance)).isEmpty();
    }

    @Test
    @DisplayName("防告警风暴冷却（degraded-event-cooldown）：连续故障冷却期内抑制重复降级告警并正常累计失败次数")
    void testDegradedEventCooldownSuppressesAlertStorm() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getHealth().setTimeout(Duration.ofMillis(80));
        properties.getHealth().setDegradedEventCooldown(Duration.ofSeconds(5));

        List<ApplicationEvent> events = new ArrayList<>();
        AtomicInteger phase = new AtomicInteger(1); // 1=DOWN, 2=TIMEOUT, 0=UP

        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(properties) {
            @Override
            protected String getComponentName() {
                return "redis";
            }

            @Override
            protected void doCheck(Health.Builder builder) throws Exception {
                switch (phase.get()) {
                    case 1 -> builder.down().withDetail("err", "unreachable");
                    case 2 -> {
                        Thread.sleep(200);
                        builder.up();
                    }
                    default -> builder.up();
                }
            }
        };
        indicator.setApplicationEventPublisher(e -> {
            if (e instanceof ApplicationEvent appEvent) {
                events.add(appEvent);
            }
        });

        // 连续 4 次故障：仅第 1 次发布 HealthProbeDegradedEvent
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        phase.set(2);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
        phase.set(1);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(HealthProbeDegradedEvent.class);

        // 恢复 UP：发布恢复事件且 consecutiveFailures 为 4
        phase.set(0);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        List<HealthProbeRecoveredEvent> recovered = events.stream()
                .filter(HealthProbeRecoveredEvent.class::isInstance)
                .map(HealthProbeRecoveredEvent.class::cast)
                .toList();
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).getPreviousStatus()).isEqualTo("DOWN");
        assertThat(recovered.get(0).getConsecutiveFailures()).isEqualTo(4);

        // 恢复后进入新故障周期：立即发布新一轮降级事件
        events.clear();
        phase.set(1);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(HealthProbeDegradedEvent.class);
    }

    @Test
    @DisplayName("防击穿缓存（cache-ttl）：并发抓取仅穿透一次探测，TTL 过期与 resetProbeState 后重新探测")
    void testCacheTtlPreventsStampedeUnderConcurrency() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getHealth().setTimeout(Duration.ofSeconds(1));
        properties.getHealth().setCacheTtl(Duration.ofMillis(120));

        AtomicInteger checkExecutions = new AtomicInteger(0);
        ThreadPoolTaskExecutor probePool = new ThreadPoolTaskExecutor();
        probePool.setCorePoolSize(2);
        probePool.setMaxPoolSize(4);
        probePool.setThreadNamePrefix("cache-probe-");
        probePool.initialize();

        ThreadPoolTaskExecutor callerPool = new ThreadPoolTaskExecutor();
        callerPool.setCorePoolSize(8);
        callerPool.setMaxPoolSize(8);
        callerPool.setThreadNamePrefix("cache-caller-");
        callerPool.initialize();

        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(properties, probePool) {
            @Override
            protected void doCheck(Health.Builder builder) throws Exception {
                int seq = checkExecutions.incrementAndGet();
                Thread.sleep(20);
                builder.up().withDetail("seq", seq);
            }
        };

        try {
            // 1. 并发 8 线程同时触发冷缓存探测 -> 仅 1 次穿透执行 doCheck
            int concurrency = 8;
            CountDownLatch startGate = new CountDownLatch(1);
            List<CompletableFuture<Health>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        startGate.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return indicator.health();
                }, callerPool));
            }
            startGate.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
            for (CompletableFuture<Health> future : futures) {
                assertThat(future.get().getStatus()).isEqualTo(Status.UP);
                assertThat(future.get().getDetails()).containsEntry("seq", 1);
            }
            assertThat(checkExecutions.get()).isEqualTo(1);

            // 2. 等待 TTL 过期后再次探测 -> 穿透第 2 次
            Thread.sleep(160);
            Health refreshed = indicator.health();
            assertThat(refreshed.getStatus()).isEqualTo(Status.UP);
            assertThat(refreshed.getDetails()).containsEntry("seq", 2);
            assertThat(checkExecutions.get()).isEqualTo(2);

            // 3. 调用 resetProbeState() 主动失效缓存 -> 立即穿透第 3 次
            indicator.resetProbeState();
            Health afterReset = indicator.health();
            assertThat(afterReset.getDetails()).containsEntry("seq", 3);
            assertThat(checkExecutions.get()).isEqualTo(3);
        } finally {
            callerPool.destroy();
            probePool.destroy();
        }
    }
}
