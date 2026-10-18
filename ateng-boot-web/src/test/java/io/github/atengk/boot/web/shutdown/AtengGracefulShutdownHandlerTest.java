package io.github.atengk.boot.web.shutdown;

import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

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
}
