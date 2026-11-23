package io.github.atengk.boot.web.shutdown;

import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kubernetes 云原生流量排空与优雅停机处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("AtengGracefulShutdownHandler 单元测试")
class AtengGracefulShutdownHandlerTest {

    @Test
    @DisplayName("测试正常排空：广播 REFUSING_TRAFFIC 事件并正确返回摘要")
    void testDrainNormal() {
        WebProperties.GracefulShutdownProperties properties = new WebProperties.GracefulShutdownProperties();
        properties.setDrainTimeout(Duration.ofMillis(30));

        AtengGracefulShutdownHandler handler = new AtengGracefulShutdownHandler(properties);
        List<AvailabilityChangeEvent<?>> events = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof AvailabilityChangeEvent<?> ace) {
                events.add(ace);
            }
        };
        handler.setApplicationEventPublisher(publisher);

        AtengGracefulShutdownHandler.DrainResult result = handler.drain();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ReadinessState.REFUSING_TRAFFIC.name());
        assertThat(result.executed()).isTrue();
        assertThat(handler.isDrained()).isTrue();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
    }

    @Test
    @DisplayName("测试状态机幂等：多次调用仅执行一次排空")
    void testDrainIdempotency() {
        WebProperties.GracefulShutdownProperties properties = new WebProperties.GracefulShutdownProperties();
        properties.setDrainTimeout(Duration.ofMillis(30));

        AtengGracefulShutdownHandler handler = new AtengGracefulShutdownHandler(properties);
        List<AvailabilityChangeEvent<?>> events = new ArrayList<>();
        handler.setApplicationEventPublisher(event -> {
            if (event instanceof AvailabilityChangeEvent<?> ace) {
                events.add(ace);
            }
        });

        // 第一次调用：执行排空
        AtengGracefulShutdownHandler.DrainResult firstResult = handler.drain();
        assertThat(firstResult.executed()).isTrue();
        assertThat(events).hasSize(1);

        // 第二次调用：幂等跳过
        AtengGracefulShutdownHandler.DrainResult secondResult = handler.drain();
        assertThat(secondResult.executed()).isFalse();
        assertThat(secondResult.status()).isEqualTo(ReadinessState.REFUSING_TRAFFIC.name());
        assertThat(events).hasSize(1); // 不会重复广播事件
    }

    @Test
    @DisplayName("测试并发调用幂等：多线程并发仅单个线程抢占排空")
    void testConcurrentDrain() throws Exception {
        WebProperties.GracefulShutdownProperties properties = new WebProperties.GracefulShutdownProperties();
        properties.setDrainTimeout(Duration.ofMillis(50));

        AtengGracefulShutdownHandler handler = new AtengGracefulShutdownHandler(properties);
        AtomicInteger eventCount = new AtomicInteger();
        handler.setApplicationEventPublisher(event -> {
            if (event instanceof AvailabilityChangeEvent<?>) {
                eventCount.incrementAndGet();
            }
        });

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<AtengGracefulShutdownHandler.DrainResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return handler.drain();
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        int executedCount = 0;
        int skippedCount = 0;
        for (Future<AtengGracefulShutdownHandler.DrainResult> future : futures) {
            AtengGracefulShutdownHandler.DrainResult res = future.get();
            if (res.executed()) {
                executedCount++;
            } else {
                skippedCount++;
            }
        }
        executor.shutdown();

        assertThat(executedCount).isEqualTo(1);
        assertThat(skippedCount).isEqualTo(threads - 1);
        assertThat(eventCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试禁用配置：不触发事件与睡眠")
    void testDisabledConfig() {
        WebProperties.GracefulShutdownProperties properties = new WebProperties.GracefulShutdownProperties();
        properties.setEnabled(false);

        AtengGracefulShutdownHandler handler = new AtengGracefulShutdownHandler(properties);
        List<Object> events = new ArrayList<>();
        handler.setApplicationEventPublisher(events::add);

        AtengGracefulShutdownHandler.DrainResult result = handler.drain();

        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(result.executed()).isFalse();
        assertThat(events).isEmpty();
        assertThat(handler.isDrained()).isFalse();
    }

    @Test
    @DisplayName("测试 SmartLifecycle 契约：start、stop 与 phase 最高优先级")
    void testSmartLifecycle() {
        WebProperties.GracefulShutdownProperties properties = new WebProperties.GracefulShutdownProperties();
        properties.setDrainTimeout(Duration.ofMillis(10));
        properties.setSmartLifecycleEnabled(true);

        AtengGracefulShutdownHandler handler = new AtengGracefulShutdownHandler(properties);
        List<AvailabilityChangeEvent<?>> events = new ArrayList<>();
        handler.setApplicationEventPublisher(event -> {
            if (event instanceof AvailabilityChangeEvent<?> ace) {
                events.add(ace);
            }
        });

        assertThat(handler.isAutoStartup()).isTrue();
        assertThat(handler.getPhase()).isEqualTo(Integer.MAX_VALUE);
        assertThat(handler.isRunning()).isFalse();

        handler.start();
        assertThat(handler.isRunning()).isTrue();
        assertThat(handler.isDrained()).isFalse();

        // 停止触发兜底排空
        handler.stop();
        assertThat(handler.isRunning()).isFalse();
        assertThat(handler.isDrained()).isTrue();
        assertThat(events).hasSize(1);

        // 重复调用 stop 不会抛出异常
        handler.stop();
    }

    @Test
    @DisplayName("测试 SmartLifecycle 禁用：stop 不触发排空")
    void testSmartLifecycleDisabled() {
        WebProperties.GracefulShutdownProperties properties = new WebProperties.GracefulShutdownProperties();
        properties.setDrainTimeout(Duration.ofMillis(10));
        properties.setSmartLifecycleEnabled(false);

        AtengGracefulShutdownHandler handler = new AtengGracefulShutdownHandler(properties);
        handler.start();
        handler.stop();

        assertThat(handler.isDrained()).isFalse();
    }

    @Test
    @DisplayName("测试在途请求排空感知：精确捕获启动时与排空后活跃请求计数并输出至 DrainResult")
    void testDrainWithInFlightRequestTracking() throws Exception {
        WebProperties webProps = new WebProperties();
        webProps.getShutdown().setDrainTimeout(Duration.ofMillis(10));

        io.github.atengk.boot.web.trace.TraceFilter traceFilter = new io.github.atengk.boot.web.trace.TraceFilter(webProps);
        AtengGracefulShutdownHandler handler = new AtengGracefulShutdownHandler(webProps, traceFilter);

        CountDownLatch inFilterLatch = new CountDownLatch(1);
        CountDownLatch releaseRequestLatch = new CountDownLatch(1);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> requestFuture = worker.submit(() -> {
                try {
                    traceFilter.doFilter(
                            new org.springframework.mock.web.MockHttpServletRequest(),
                            new org.springframework.mock.web.MockHttpServletResponse(),
                            (req, res) -> {
                                inFilterLatch.countDown();
                                try {
                                    releaseRequestLatch.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            inFilterLatch.await();
            assertThat(handler.getInFlightRequestCount()).isEqualTo(1L);

            // 通过探针状态事件钩子确定性触发在途请求放行并等待其完全退出 TraceFilter，避免并发调度竞态
            handler.setApplicationEventPublisher(event -> {
                releaseRequestLatch.countDown();
                try {
                    requestFuture.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            AtengGracefulShutdownHandler.DrainResult result = handler.drain();

            assertThat(result.executed()).isTrue();
            assertThat(result.inFlightRequestsAtStart()).isEqualTo(1L);
            assertThat(result.inFlightRequestsRemaining()).isZero();
            assertThat(result.toMap())
                    .containsEntry("inFlightRequestsAtStart", 1L)
                    .containsEntry("inFlightRequestsRemaining", 0L);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("测试 DrainResult 四参数向后兼容构造器")
    void testDrainResultBackwardCompatibleConstructor() {
        AtengGracefulShutdownHandler.DrainResult legacy =
                new AtengGracefulShutdownHandler.DrainResult("REFUSING_TRAFFIC", true, "30s", "ok");
        assertThat(legacy.inFlightRequestsAtStart()).isZero();
        assertThat(legacy.inFlightRequestsRemaining()).isZero();
    }

    @Test
    @DisplayName("两阶段自适应排空：未配 quietPeriod 保持等待全量 drainTimeout；配置 quietPeriod < drainTimeout 在途归零立即提前结束")
    void testTwoPhaseAdaptiveDrainBehavior() throws Exception {
        // 1. 未配置 quietPeriod 时，保持与既有行为一致，等待完整 drainTimeout (120ms)
        WebProperties.GracefulShutdownProperties legacyProps = new WebProperties.GracefulShutdownProperties();
        legacyProps.setDrainTimeout(Duration.ofMillis(120));
        AtengGracefulShutdownHandler legacyHandler = new AtengGracefulShutdownHandler(legacyProps);
        long legacyStart = System.nanoTime();
        legacyHandler.drain();
        long legacyElapsedMs = Duration.ofNanos(System.nanoTime() - legacyStart).toMillis();
        assertThat(legacyElapsedMs).isGreaterThanOrEqualTo(90L);

        // 2. 配置 quietPeriod < drainTimeout 且零在途请求时，等待 quietPeriod 后无需死等 1500ms 即刻返回
        WebProperties.GracefulShutdownProperties zeroInFlightProps = new WebProperties.GracefulShutdownProperties();
        zeroInFlightProps.setQuietPeriod(Duration.ofMillis(20));
        zeroInFlightProps.setDrainTimeout(Duration.ofMillis(1500));
        zeroInFlightProps.setPollInterval(Duration.ofMillis(15));
        AtengGracefulShutdownHandler fastHandler = new AtengGracefulShutdownHandler(zeroInFlightProps);
        long fastStart = System.nanoTime();
        AtengGracefulShutdownHandler.DrainResult fastResult = fastHandler.drain();
        long fastElapsedMs = Duration.ofNanos(System.nanoTime() - fastStart).toMillis();
        assertThat(fastResult.executed()).isTrue();
        assertThat(fastElapsedMs).isLessThan(500L);

        // 3. 存在 1 个在途业务请求时，阶段二按 pollInterval 轮询至请求结束即刻提前返回
        WebProperties activeWebProps = new WebProperties();
        activeWebProps.getShutdown().setQuietPeriod(Duration.ofMillis(15));
        activeWebProps.getShutdown().setDrainTimeout(Duration.ofMillis(1500));
        activeWebProps.getShutdown().setPollInterval(Duration.ofMillis(15));

        TraceFilter traceFilter = new TraceFilter(activeWebProps);
        AtengGracefulShutdownHandler adaptiveHandler = new AtengGracefulShutdownHandler(activeWebProps, traceFilter);

        CountDownLatch inFlightReady = new CountDownLatch(1);
        CountDownLatch releaseInFlight = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> {
                try {
                    traceFilter.doFilter(
                            new MockHttpServletRequest("GET", "/api/biz"),
                            new MockHttpServletResponse(),
                            (req, res) -> {
                                inFlightReady.countDown();
                                try {
                                    releaseInFlight.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            inFlightReady.await();
            // 在另起线程于 60ms 后放行在途业务请求
            executor.submit(() -> {
                try {
                    Thread.sleep(60L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    releaseInFlight.countDown();
                }
            });

            long adaptiveStart = System.nanoTime();
            AtengGracefulShutdownHandler.DrainResult adaptiveResult = adaptiveHandler.drain();
            long adaptiveElapsedMs = Duration.ofNanos(System.nanoTime() - adaptiveStart).toMillis();

            assertThat(adaptiveResult.executed()).isTrue();
            assertThat(adaptiveResult.inFlightRequestsAtStart()).isEqualTo(1L);
            assertThat(adaptiveResult.inFlightRequestsRemaining()).isZero();
            assertThat(adaptiveElapsedMs).isLessThan(600L);
        } finally {
            releaseInFlight.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("两阶段自适应排空边界场景：quietPeriod=0、quietPeriod>drainTimeout 钳位、超时未归零残留及中断恢复")
    void testTwoPhaseAdaptiveDrainEdgeCases() throws Exception {
        // 1. quietPeriod = ZERO 且存在延迟放行在途请求：跳过静默直接进入轮询，归零即提前退出
        WebProperties zeroQuietProps = new WebProperties();
        zeroQuietProps.getShutdown().setQuietPeriod(Duration.ZERO);
        zeroQuietProps.getShutdown().setDrainTimeout(Duration.ofMillis(1000));
        zeroQuietProps.getShutdown().setPollInterval(Duration.ofMillis(10));
        TraceFilter zeroQuietFilter = new TraceFilter(zeroQuietProps);
        AtengGracefulShutdownHandler zeroQuietHandler = new AtengGracefulShutdownHandler(zeroQuietProps, zeroQuietFilter);

        CountDownLatch inFlightStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> {
                try {
                    zeroQuietFilter.doFilter(
                            new MockHttpServletRequest("GET", "/api/slow"),
                            new MockHttpServletResponse(),
                            (req, res) -> {
                                inFlightStarted.countDown();
                                try {
                                    releaseRequest.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            inFlightStarted.await();
            executor.submit(() -> {
                try {
                    Thread.sleep(40L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    releaseRequest.countDown();
                }
            });

            long start = System.nanoTime();
            AtengGracefulShutdownHandler.DrainResult zeroQuietResult = zeroQuietHandler.drain();
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(zeroQuietResult.inFlightRequestsAtStart()).isEqualTo(1L);
            assertThat(zeroQuietResult.inFlightRequestsRemaining()).isZero();
            assertThat(elapsedMs).isLessThan(500L);
        } finally {
            releaseRequest.countDown();
            executor.shutdownNow();
        }

        // 2. quietPeriod > drainTimeout 时钳位至 drainTimeout 上限，且超时后仍有在途请求时如实返回剩余数
        WebProperties clampedAndTimeoutProps = new WebProperties();
        clampedAndTimeoutProps.getShutdown().setDrainTimeout(Duration.ofMillis(60));
        clampedAndTimeoutProps.getShutdown().setQuietPeriod(Duration.ofMillis(500));
        clampedAndTimeoutProps.getShutdown().setPollInterval(Duration.ZERO);
        TraceFilter stuckFilter = new TraceFilter(clampedAndTimeoutProps);
        AtengGracefulShutdownHandler timeoutHandler = new AtengGracefulShutdownHandler(clampedAndTimeoutProps, stuckFilter);

        CountDownLatch stuckStarted = new CountDownLatch(1);
        CountDownLatch stuckRelease = new CountDownLatch(1);
        ExecutorService stuckExecutor = Executors.newSingleThreadExecutor();
        try {
            stuckExecutor.submit(() -> {
                try {
                    stuckFilter.doFilter(
                            new MockHttpServletRequest("GET", "/api/stuck"),
                            new MockHttpServletResponse(),
                            (req, res) -> {
                                stuckStarted.countDown();
                                try {
                                    stuckRelease.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            stuckStarted.await();

            long timeoutStart = System.nanoTime();
            AtengGracefulShutdownHandler.DrainResult timeoutResult = timeoutHandler.drain();
            long timeoutElapsedMs = Duration.ofNanos(System.nanoTime() - timeoutStart).toMillis();
            assertThat(timeoutElapsedMs).isLessThan(300L);
            assertThat(timeoutResult.inFlightRequestsAtStart()).isEqualTo(1L);
            assertThat(timeoutResult.inFlightRequestsRemaining()).isEqualTo(1L);
        } finally {
            stuckRelease.countDown();
            stuckExecutor.shutdownNow();
        }

        // 3. 轮询等待期间线程被中断时正确恢复中断标志并退出
        WebProperties interruptProps = new WebProperties();
        interruptProps.getShutdown().setQuietPeriod(Duration.ofMillis(200));
        interruptProps.getShutdown().setDrainTimeout(Duration.ofMillis(500));
        AtengGracefulShutdownHandler interruptHandler = new AtengGracefulShutdownHandler(interruptProps);
        Thread.currentThread().interrupt();
        try {
            interruptHandler.drain();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
