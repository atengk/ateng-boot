package io.github.atengk.boot.core.autoconfigure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.MDC;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.properties.AsyncProperties;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文感知受管异步线程池自动装配单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("上下文感知受管异步线程池自动装配测试")
class AtengAsyncAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAsyncAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("默认配置下成功装配 taskExecutor 与 applicationTaskExecutor，属性自适应 CPU 核心数")
    void shouldAutoConfigureDefaultAsyncExecutor() {
        int expectedCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        contextRunner.run(context -> {
            assertThat(context).hasBean("taskExecutor");
            assertThat(context).hasBean("applicationTaskExecutor");
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            assertThat(context).hasSingleBean(AsyncConfigurer.class);

            ThreadPoolTaskExecutor executor = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(expectedCores);
            assertThat(executor.getMaxPoolSize()).isEqualTo(expectedCores * 2);
            assertThat(executor.getKeepAliveSeconds()).isEqualTo(60);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("ateng-async-");

            AsyncConfigurer configurer = context.getBean(AsyncConfigurer.class);
            assertThat(configurer.getAsyncExecutor()).isSameAs(executor);
            assertThat(configurer.getAsyncUncaughtExceptionHandler()).isNotNull();
        });
    }

    @Test
    @DisplayName("配置自定义 ateng.boot.async.* 属性时成功覆盖默认线程池参数")
    void shouldApplyCustomConfigurationProperties() {
        contextRunner.withPropertyValues(
                "ateng.boot.async.core-pool-size=4",
                "ateng.boot.async.max-pool-size=8",
                "ateng.boot.async.queue-capacity=200",
                "ateng.boot.async.keep-alive-seconds=30",
                "ateng.boot.async.thread-name-prefix=custom-async-"
        ).run(context -> {
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor executor = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(8);
            assertThat(executor.getKeepAliveSeconds()).isEqualTo(30);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("custom-async-");
        });
    }

    @Test
    @DisplayName("配置强类型 Duration 格式参数时成功解析并在关闭时优雅等待")
    void shouldSupportStronglyTypedDurationProperties() {
        contextRunner.withPropertyValues(
                "ateng.boot.async.keep-alive=45s",
                "ateng.boot.async.await-termination=90s",
                "ateng.boot.async.wait-for-tasks-to-complete-on-shutdown=true"
        ).run(context -> {
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor executor = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getKeepAliveSeconds()).isEqualTo(45);

            AsyncProperties properties = context.getBean(AsyncProperties.class);
            assertThat(properties.getKeepAlive()).isEqualTo(java.time.Duration.ofSeconds(45));
            assertThat(properties.getAwaitTermination()).isEqualTo(java.time.Duration.ofSeconds(90));
        });
    }

    @Test
    @DisplayName("配置 ateng.boot.async.enabled=false 时禁用异步线程池装配")
    void shouldDisableAsyncAutoConfigurationWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.async.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("taskExecutor");
                    assertThat(context).doesNotHaveBean(AtengAsyncAutoConfiguration.class);
                });
    }

    @Test
    @DisplayName("当下游应用自定义 taskExecutor Bean 时自动装配防御性退避")
    void shouldBackOffWhenCustomTaskExecutorBeanExists() {
        contextRunner.withUserConfiguration(CustomExecutorConfig.class)
                .run(context -> {
                    assertThat(context).hasBean("taskExecutor");
                    Executor executor = context.getBean("taskExecutor", Executor.class);
                    assertThat(executor).isSameAs(CustomExecutorConfig.CUSTOM_EXECUTOR);
                });
    }

    @Test
    @DisplayName("开启 spring.threads.virtual.enabled=true 时在 JDK 21+ 下成功装配 SimpleAsyncTaskExecutor 虚拟线程执行器")
    @EnabledForJreRange(min = JRE.JAVA_21)
    void shouldAutoConfigureVirtualThreadAsyncExecutorWhenEnabled() {
        contextRunner.withPropertyValues("spring.threads.virtual.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("taskExecutor");
                    assertThat(context).hasBean("applicationTaskExecutor");
                    assertThat(context).hasSingleBean(SimpleAsyncTaskExecutor.class);
                    assertThat(context).doesNotHaveBean(ThreadPoolTaskExecutor.class);
                    assertThat(context).hasSingleBean(AsyncConfigurer.class);

                    SimpleAsyncTaskExecutor executor = context.getBean("taskExecutor", SimpleAsyncTaskExecutor.class);
                    assertThat(executor.getThreadNamePrefix()).isEqualTo("ateng-vt-async-");
                    assertThat(executor.getConcurrencyLimit()).isEqualTo(SimpleAsyncTaskExecutor.UNBOUNDED_CONCURRENCY);

                    AsyncConfigurer configurer = context.getBean(AsyncConfigurer.class);
                    assertThat(configurer.getAsyncExecutor()).isSameAs(executor);
                });
    }

    @Test
    @DisplayName("开启虚拟线程并配置自定义 virtual-thread-name-prefix 与 concurrency-limit 时属性成功生效")
    @EnabledForJreRange(min = JRE.JAVA_21)
    void shouldApplyCustomVirtualThreadProperties() {
        contextRunner.withPropertyValues(
                "spring.threads.virtual.enabled=true",
                "ateng.boot.async.virtual-thread-name-prefix=custom-vt-",
                "ateng.boot.async.concurrency-limit=64"
        ).run(context -> {
            assertThat(context).hasSingleBean(SimpleAsyncTaskExecutor.class);
            SimpleAsyncTaskExecutor executor = context.getBean("taskExecutor", SimpleAsyncTaskExecutor.class);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("custom-vt-");
            assertThat(executor.getConcurrencyLimit()).isEqualTo(64);
        });
    }

    @Test
    @DisplayName("配置多业务子池时成功装配各业务池及别名，且主池 taskExecutor 保留 @Primary 兜底路由")
    void shouldAutoConfigureMultipleAsyncPoolsAndAliasesWithPrimaryFallback() {
        contextRunner.withPropertyValues(
                "ateng.boot.async.pools.export.core-pool-size=2",
                "ateng.boot.async.pools.export.max-pool-size=4",
                "ateng.boot.async.pools.export.queue-capacity=50",
                "ateng.boot.async.pools.reportTaskExecutor.core-pool-size=1",
                "ateng.boot.async.pools.reportTaskExecutor.max-pool-size=2"
        ).run(context -> {
            // 验证主池存在且被 @Primary 标记，按 Executor 类型获取精准命中主池
            assertThat(context).hasBean("taskExecutor");
            assertThat(context).hasBean("applicationTaskExecutor");
            ThreadPoolTaskExecutor primaryExecutor = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(context.getBean(Executor.class)).isSameAs(primaryExecutor);

            // 验证子池 exportTaskExecutor 与别名 export
            assertThat(context).hasBean("exportTaskExecutor");
            assertThat(context).hasBean("export");
            ThreadPoolTaskExecutor exportExecutor = context.getBean("exportTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(context.getBean("export", ThreadPoolTaskExecutor.class)).isSameAs(exportExecutor);
            assertThat(exportExecutor.getCorePoolSize()).isEqualTo(2);
            assertThat(exportExecutor.getMaxPoolSize()).isEqualTo(4);
            assertThat(exportExecutor.getQueueCapacity()).isEqualTo(50);
            assertThat(exportExecutor.getThreadNamePrefix()).isEqualTo("ateng-async-export-");

            // 验证子池 reportTaskExecutor 与截断别名 report
            assertThat(context).hasBean("reportTaskExecutor");
            assertThat(context).hasBean("report");
            ThreadPoolTaskExecutor reportExecutor = context.getBean("reportTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(context.getBean("report", ThreadPoolTaskExecutor.class)).isSameAs(reportExecutor);
            assertThat(reportExecutor.getCorePoolSize()).isEqualTo(1);
            assertThat(reportExecutor.getThreadNamePrefix()).isEqualTo("ateng-async-report-");
        });
    }

    @Test
    @DisplayName("子池支持自定义 thread-name-prefix 覆盖自动推导，且支持 enabled=false 禁用特定子池")
    void shouldApplyCustomPrefixAndSkipDisabledPools() {
        contextRunner.withPropertyValues(
                "ateng.boot.async.pools.billing.thread-name-prefix=bill-pool-",
                "ateng.boot.async.pools.billing.core-pool-size=3",
                "ateng.boot.async.pools.disabledPool.enabled=false"
        ).run(context -> {
            assertThat(context).hasBean("billingTaskExecutor");
            assertThat(context).hasBean("billing");
            ThreadPoolTaskExecutor billingExecutor = context.getBean("billingTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(billingExecutor.getThreadNamePrefix()).isEqualTo("bill-pool-");
            assertThat(billingExecutor.getCorePoolSize()).isEqualTo(3);

            // 验证禁用的子池不被注册
            assertThat(context).doesNotHaveBean("disabledPoolTaskExecutor");
            assertThat(context).doesNotHaveBean("disabledPool");
        });
    }

    @Test
    @DisplayName("即使全局启用虚拟线程，子池显式配置 mode=PLATFORM 时仍装配为 ThreadPoolTaskExecutor 平台线程池")
    @EnabledForJreRange(min = JRE.JAVA_21)
    void shouldHonorPlatformModeExplicitlyEvenIfVirtualEnabled() {
        contextRunner.withPropertyValues(
                "spring.threads.virtual.enabled=true",
                "ateng.boot.async.pools.compute.mode=PLATFORM",
                "ateng.boot.async.pools.compute.core-pool-size=4"
        ).run(context -> {
            // 全局主池为 SimpleAsyncTaskExecutor
            assertThat(context.getBean("taskExecutor")).isInstanceOf(SimpleAsyncTaskExecutor.class);

            // 业务子池 compute 显式声明 PLATFORM，舱壁隔离装配为 ThreadPoolTaskExecutor
            assertThat(context).hasBean("computeTaskExecutor");
            assertThat(context.getBean("computeTaskExecutor")).isInstanceOf(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor computeExecutor = context.getBean("computeTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(computeExecutor.getCorePoolSize()).isEqualTo(4);
            assertThat(computeExecutor.getThreadNamePrefix()).isEqualTo("ateng-async-compute-");
        });
    }

    @Test
    @DisplayName("子池显式配置 mode=VIRTUAL 时在 JDK 21+ 下独立装配为 SimpleAsyncTaskExecutor 并自动推导 vt 前缀")
    @EnabledForJreRange(min = JRE.JAVA_21)
    void shouldHonorVirtualModeForSpecificPool() {
        contextRunner.withPropertyValues(
                "ateng.boot.async.pools.ioHeavy.mode=VIRTUAL",
                "ateng.boot.async.pools.ioHeavy.concurrency-limit=32"
        ).run(context -> {
            // 全局未开启虚拟线程时，主池仍为 ThreadPoolTaskExecutor
            assertThat(context.getBean("taskExecutor")).isInstanceOf(ThreadPoolTaskExecutor.class);

            // 子池 ioHeavy 独立为 SimpleAsyncTaskExecutor
            assertThat(context).hasBean("ioHeavyTaskExecutor");
            assertThat(context.getBean("ioHeavyTaskExecutor")).isInstanceOf(SimpleAsyncTaskExecutor.class);
            SimpleAsyncTaskExecutor ioHeavyExecutor = context.getBean("ioHeavyTaskExecutor", SimpleAsyncTaskExecutor.class);
            assertThat(ioHeavyExecutor.getThreadNamePrefix()).isEqualTo("ateng-vt-async-ioHeavy-");
            assertThat(ioHeavyExecutor.getConcurrencyLimit()).isEqualTo(32);
        });
    }

    @Test
    @DisplayName("子池异步并发调度中四维上下文（MDC、User、TenantId、IgnoreTenant）100% 正确穿透且任务结束后严格复原现场")
    void shouldPropagate4DContextAndCleanUpWorkerThreadInSubPool() throws Exception {
        contextRunner.withPropertyValues(
                "ateng.boot.async.pools.audit.core-pool-size=1",
                "ateng.boot.async.pools.audit.max-pool-size=1",
                "ateng.boot.async.pools.audit.queue-capacity=10"
        ).run(context -> {
            ThreadPoolTaskExecutor auditExecutor = context.getBean("auditTaskExecutor", ThreadPoolTaskExecutor.class);

            // 1. 设置主线程四维上下文
            MDC.put("traceId", "trace-audit-888");
            UserContextHolder.set("user-operator-999");
            TenantContextHolder.setTenantId("tenant-core-100");
            TenantContextHolder.setIgnoreTenant(false);

            AtomicReference<String> capturedTraceId = new AtomicReference<>();
            AtomicReference<String> capturedUserId = new AtomicReference<>();
            AtomicReference<String> capturedTenantId = new AtomicReference<>();
            AtomicBoolean capturedIgnoreTenant = new AtomicBoolean();

            // 2. 提交任务，验证子线程抓取到上下文快照
            Future<?> future1 = auditExecutor.submit(() -> {
                capturedTraceId.set(MDC.get("traceId"));
                capturedUserId.set(UserContextHolder.get());
                capturedTenantId.set(TenantContextHolder.getTenantId());
                capturedIgnoreTenant.set(TenantContextHolder.isIgnoreTenant());
            });
            future1.get(3, TimeUnit.SECONDS);

            assertThat(capturedTraceId.get()).isEqualTo("trace-audit-888");
            assertThat(capturedUserId.get()).isEqualTo("user-operator-999");
            assertThat(capturedTenantId.get()).isEqualTo("tenant-core-100");
            assertThat(capturedIgnoreTenant.get()).isFalse();

            // 3. 验证 IgnoreTenant 标志位穿透
            TenantContextHolder.setIgnoreTenant(true);
            AtomicBoolean capturedIgnoreTenant2 = new AtomicBoolean();
            Future<?> future2 = auditExecutor.submit(() -> {
                capturedIgnoreTenant2.set(TenantContextHolder.isIgnoreTenant());
            });
            future2.get(3, TimeUnit.SECONDS);
            assertThat(capturedIgnoreTenant2.get()).isTrue();

            // 4. 清理主线程上下文，验证线程池工作线程现场已在任务完成后严格恢复纯净，杜绝线程污染
            MDC.clear();
            UserContextHolder.clear();
            TenantContextHolder.clear();

            Future<String> traceCheck = auditExecutor.submit(() -> MDC.get("traceId"));
            Future<String> userCheck = auditExecutor.submit(UserContextHolder::get);
            Future<String> tenantCheck = auditExecutor.submit(TenantContextHolder::getTenantId);
            Future<Boolean> ignoreCheck = auditExecutor.submit(TenantContextHolder::isIgnoreTenant);

            assertThat(traceCheck.get(3, TimeUnit.SECONDS)).isNull();
            assertThat(userCheck.get(3, TimeUnit.SECONDS)).isNull();
            assertThat(tenantCheck.get(3, TimeUnit.SECONDS)).isNull();
            assertThat(ignoreCheck.get(3, TimeUnit.SECONDS)).isFalse();
        });
    }

    @Test
    @DisplayName("子池配置 rejected-policy=ABORT 饱和时触发 RejectedExecutionException 外部快速失败")
    void shouldTriggerAbortPolicyWhenSaturated() throws Exception {
        contextRunner.withPropertyValues(
                "ateng.boot.async.pools.abortPool.core-pool-size=1",
                "ateng.boot.async.pools.abortPool.max-pool-size=1",
                "ateng.boot.async.pools.abortPool.queue-capacity=1",
                "ateng.boot.async.pools.abortPool.rejected-policy=ABORT"
        ).run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("abortPoolTaskExecutor", ThreadPoolTaskExecutor.class);
            CountDownLatch blockLatch = new CountDownLatch(1);
            CountDownLatch taskStarted = new CountDownLatch(1);

            try {
                // 任务 1：占用唯一的 core 线程
                executor.execute(() -> {
                    taskStarted.countDown();
                    try {
                        blockLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(taskStarted.await(3, TimeUnit.SECONDS)).isTrue();

                // 任务 2：填满容量为 1 的队列
                executor.execute(() -> {});

                // 任务 3：触发饱和拒绝策略 ABORT
                assertThatThrownBy(() -> executor.execute(() -> {}))
                        .isInstanceOf(RejectedExecutionException.class);
            } finally {
                blockLatch.countDown();
            }
        });
    }

    @Test
    @DisplayName("子池配置 rejected-policy=CALLER_RUNS 饱和时在调用方主线程同步回退执行")
    void shouldTriggerCallerRunsPolicyWhenSaturated() throws Exception {
        contextRunner.withPropertyValues(
                "ateng.boot.async.pools.callerPool.core-pool-size=1",
                "ateng.boot.async.pools.callerPool.max-pool-size=1",
                "ateng.boot.async.pools.callerPool.queue-capacity=1",
                "ateng.boot.async.pools.callerPool.rejected-policy=CALLER_RUNS"
        ).run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("callerPoolTaskExecutor", ThreadPoolTaskExecutor.class);
            CountDownLatch blockLatch = new CountDownLatch(1);
            CountDownLatch taskStarted = new CountDownLatch(1);
            AtomicReference<String> callerThreadName = new AtomicReference<>();

            try {
                // 任务 1：占用唯一的 core 线程
                executor.execute(() -> {
                    taskStarted.countDown();
                    try {
                        blockLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(taskStarted.await(3, TimeUnit.SECONDS)).isTrue();

                // 任务 2：填满容量为 1 的队列
                executor.execute(() -> {});

                // 任务 3：饱和后由调用方线程同步执行
                executor.execute(() -> callerThreadName.set(Thread.currentThread().getName()));

                assertThat(callerThreadName.get()).isEqualTo(Thread.currentThread().getName());
            } finally {
                blockLatch.countDown();
            }
        });
    }

    @Test
    @DisplayName("子池配置 rejected-policy=DISCARD 与 DISCARD_OLDEST 饱和时正确丢弃新任务或队头老任务")
    void shouldTriggerDiscardAndDiscardOldestPoliciesWhenSaturated() throws Exception {
        // 1. 验证 DISCARD
        contextRunner.withPropertyValues(
                "ateng.boot.async.pools.discardPool.core-pool-size=1",
                "ateng.boot.async.pools.discardPool.max-pool-size=1",
                "ateng.boot.async.pools.discardPool.queue-capacity=1",
                "ateng.boot.async.pools.discardPool.rejected-policy=DISCARD"
        ).run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("discardPoolTaskExecutor", ThreadPoolTaskExecutor.class);
            CountDownLatch blockLatch = new CountDownLatch(1);
            CountDownLatch taskStarted = new CountDownLatch(1);
            AtomicBoolean discardedTaskRan = new AtomicBoolean(false);

            try {
                executor.execute(() -> {
                    taskStarted.countDown();
                    try {
                        blockLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(taskStarted.await(3, TimeUnit.SECONDS)).isTrue();

                // 队列填满
                executor.execute(() -> {});

                // 饱和任务静默丢弃
                executor.execute(() -> discardedTaskRan.set(true));
            } finally {
                blockLatch.countDown();
            }

            // 验证被丢弃的任务未执行
            assertThat(discardedTaskRan.get()).isFalse();
        });

        // 2. 验证 DISCARD_OLDEST
        contextRunner.withPropertyValues(
                "ateng.boot.async.pools.discardOldestPool.core-pool-size=1",
                "ateng.boot.async.pools.discardOldestPool.max-pool-size=1",
                "ateng.boot.async.pools.discardOldestPool.queue-capacity=1",
                "ateng.boot.async.pools.discardOldestPool.rejected-policy=DISCARD_OLDEST"
        ).run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("discardOldestPoolTaskExecutor", ThreadPoolTaskExecutor.class);
            CountDownLatch blockLatch = new CountDownLatch(1);
            CountDownLatch taskStarted = new CountDownLatch(1);
            AtomicBoolean oldTaskRan = new AtomicBoolean(false);
            AtomicBoolean newestTaskRan = new AtomicBoolean(false);
            CountDownLatch newestFinished = new CountDownLatch(1);

            try {
                executor.execute(() -> {
                    taskStarted.countDown();
                    try {
                        blockLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(taskStarted.await(3, TimeUnit.SECONDS)).isTrue();

                // 任务 2 进队列
                executor.execute(() -> oldTaskRan.set(true));

                // 任务 3 挤掉队列中的任务 2
                executor.execute(() -> {
                    newestTaskRan.set(true);
                    newestFinished.countDown();
                });
            } finally {
                blockLatch.countDown();
            }

            assertThat(newestFinished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(oldTaskRan.get()).isFalse();
            assertThat(newestTaskRan.get()).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomExecutorConfig {
        static final ThreadPoolTaskExecutor CUSTOM_EXECUTOR = new ThreadPoolTaskExecutor();

        static {
            CUSTOM_EXECUTOR.setThreadNamePrefix("user-defined-");
        }

        @Bean(name = "taskExecutor")
        public Executor taskExecutor() {
            return CUSTOM_EXECUTOR;
        }
    }
}
