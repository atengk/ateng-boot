package io.github.atengk.boot.observability.metrics;

import io.github.atengk.boot.observability.event.ThreadPoolRejectedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AtengThreadPoolMeterBinder 线程池度量绑定器单元测试
 * 验证自动扫描度量注册、任务拒绝计数递增及 ThreadPoolRejectedEvent 领域事件发布联动
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengThreadPoolMeterBinder 单元测试")
class AtengThreadPoolMeterBinderTest {

    private AnnotationConfigApplicationContext context;
    private MeterRegistry registry;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Configuration
    static class TestThreadPoolConfig {

        @Bean
        public List<ThreadPoolRejectedEvent> capturedRejectedEvents() {
            return new CopyOnWriteArrayList<>();
        }

        @Bean
        public ApplicationListener<ThreadPoolRejectedEvent> threadPoolRejectedEventListener(List<ThreadPoolRejectedEvent> capturedRejectedEvents) {
            return capturedRejectedEvents::add;
        }

        @Bean("customTaskExecutor")
        public ThreadPoolTaskExecutor customTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setQueueCapacity(2);
            executor.setThreadNamePrefix("custom-test-");
            executor.initialize();
            return executor;
        }

        @Bean("rawThreadPoolExecutor")
        public ThreadPoolExecutor rawThreadPoolExecutor() {
            return new ThreadPoolExecutor(
                    1, 2, 60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(5)
            );
        }

        @Bean("customSimpleAsyncExecutor")
        public SimpleAsyncTaskExecutor customSimpleAsyncExecutor() {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("custom-async-");
            executor.setConcurrencyLimit(10);
            return executor;
        }
    }

    @Test
    @DisplayName("验证自动扫描并注册全量线程池运行度量指标")
    void testBindThreadPoolMetrics() {
        context = new AnnotationConfigApplicationContext(TestThreadPoolConfig.class);
        registry = new SimpleMeterRegistry();

        AtengThreadPoolMeterBinder binder = new AtengThreadPoolMeterBinder();
        binder.setApplicationContext(context);
        binder.bindTo(registry);

        // 验证 customTaskExecutor 指标与 Tags
        assertThat(registry.get("ateng.threadpool.core.size").tag("pool", "customTaskExecutor").tag("type", "platform").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("ateng.threadpool.max.size").tag("pool", "customTaskExecutor").tag("type", "platform").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("ateng.threadpool.queue.size").tag("pool", "customTaskExecutor").tag("type", "platform").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("ateng.threadpool.queue.remaining.capacity").tag("pool", "customTaskExecutor").tag("type", "platform").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("ateng.threadpool.active.count").tag("pool", "customTaskExecutor").tag("type", "platform").gauge().value()).isEqualTo(0.0);

        // 验证 rawThreadPoolExecutor 指标
        assertThat(registry.get("ateng.threadpool.core.size").tag("pool", "rawThreadPoolExecutor").tag("type", "platform").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("ateng.threadpool.max.size").tag("pool", "rawThreadPoolExecutor").tag("type", "platform").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("ateng.threadpool.queue.remaining.capacity").tag("pool", "rawThreadPoolExecutor").tag("type", "platform").gauge().value()).isEqualTo(5.0);

        // 验证 customSimpleAsyncExecutor (虚拟线程/异步执行器) 指标
        assertThat(registry.get("ateng.threadpool.concurrency.limit").tag("pool", "customSimpleAsyncExecutor").tag("type", "virtual").gauge().value()).isEqualTo(10.0);
        assertThat(registry.get("ateng.threadpool.throttle.active").tag("pool", "customSimpleAsyncExecutor").tag("type", "virtual").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("验证并发打满队列触发任务拒绝时 rejected.tasks.total 指标准确递增并广播发布 ThreadPoolRejectedEvent")
    void testRejectedTasksCounterIncrement() throws InterruptedException {
        context = new AnnotationConfigApplicationContext(TestThreadPoolConfig.class);
        registry = new SimpleMeterRegistry();

        AtengThreadPoolMeterBinder binder = new AtengThreadPoolMeterBinder();
        binder.setApplicationContext(context);
        binder.bindTo(registry);

        ThreadPoolTaskExecutor executor = context.getBean("customTaskExecutor", ThreadPoolTaskExecutor.class);

        // 当前配置：core=2, max=4, queue=2。
        // JDK ThreadPoolExecutor 调度策略：先占满 core(2)，再排入 queue(2)，队列满后再扩容至 max(4)。
        // 因此需提交 6 个任务以占满全部 4 个工作线程与 2 个排队槽位。
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startLatch = new CountDownLatch(4); // 阻塞 4 个工作线程

        for (int i = 0; i < 6; i++) {
            executor.execute(() -> {
                startLatch.countDown();
                try {
                    blockLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 等待 4 个工作线程全部就绪进入阻塞状态
        assertThat(startLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // 此时 4 个工作线程已满且队列 2 个槽位已排满，再提交第 7 个任务必定触发拒绝策略
        assertThatThrownBy(() -> executor.execute(() -> {}))
                .isInstanceOf(RejectedExecutionException.class);

        // 验证 Micrometer Counter 捕获到 1 次拒绝
        double rejectedCount = registry.get("ateng.threadpool.rejected.tasks.total")
                .tag("pool", "customTaskExecutor")
                .functionCounter()
                .count();

        assertThat(rejectedCount).isEqualTo(1.0);

        // 验证 Spring 容器中发布并捕获到 ThreadPoolRejectedEvent
        @SuppressWarnings("unchecked")
        List<ThreadPoolRejectedEvent> capturedEvents = context.getBean("capturedRejectedEvents", List.class);
        assertThat(capturedEvents).hasSize(1);
        ThreadPoolRejectedEvent event = capturedEvents.get(0);
        assertThat(event.getPoolName()).isEqualTo("customTaskExecutor");
        assertThat(event.getCorePoolSize()).isEqualTo(2);
        assertThat(event.getMaxPoolSize()).isEqualTo(4);
        assertThat(event.getCumulativeRejectedCount()).isEqualTo(1L);
        assertThat(event.getTimestamp()).isPositive();

        // 释放阻塞线程
        blockLatch.countDown();
    }

    @Test
    @DisplayName("验证原生 ThreadPoolExecutor 触发拒绝时正确发布 ThreadPoolRejectedEvent")
    void testRawThreadPoolExecutorRejectionPublishesEvent() {
        context = new AnnotationConfigApplicationContext(TestThreadPoolConfig.class);
        registry = new SimpleMeterRegistry();

        AtengThreadPoolMeterBinder binder = new AtengThreadPoolMeterBinder();
        binder.setApplicationContext(context);
        binder.bindTo(registry);

        ThreadPoolExecutor rawExecutor = context.getBean("rawThreadPoolExecutor", ThreadPoolExecutor.class);
        RejectedExecutionHandler handler = rawExecutor.getRejectedExecutionHandler();
        assertThat(handler).isInstanceOf(TrackingRejectedExecutionHandler.class);
        TrackingRejectedExecutionHandler trackingHandler = (TrackingRejectedExecutionHandler) handler;
        assertThat(trackingHandler.getPoolName()).isEqualTo("rawThreadPoolExecutor");

        Runnable task = () -> {};
        assertThatThrownBy(() -> trackingHandler.rejectedExecution(task, rawExecutor))
                .isInstanceOf(RejectedExecutionException.class);

        @SuppressWarnings("unchecked")
        List<ThreadPoolRejectedEvent> capturedEvents = context.getBean("capturedRejectedEvents", List.class);
        assertThat(capturedEvents).isNotEmpty();
        ThreadPoolRejectedEvent event = capturedEvents.get(capturedEvents.size() - 1);
        assertThat(event.getPoolName()).isEqualTo("rawThreadPoolExecutor");
        assertThat(event.getCorePoolSize()).isEqualTo(1);
        assertThat(event.getMaxPoolSize()).isEqualTo(2);
        assertThat(event.getRemainingQueueCapacity()).isEqualTo(5);
        assertThat(event.getCumulativeRejectedCount()).isEqualTo(1L);
    }
}
