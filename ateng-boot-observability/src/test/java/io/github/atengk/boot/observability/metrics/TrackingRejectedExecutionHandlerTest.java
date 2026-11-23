package io.github.atengk.boot.observability.metrics;

import io.github.atengk.boot.observability.event.ThreadPoolRejectedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TrackingRejectedExecutionHandler 任务拒绝追踪装饰器单元测试
 * 验证计数器自增、事件广播发布、异常熔断防御及幂等包装
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("TrackingRejectedExecutionHandler 单元测试")
class TrackingRejectedExecutionHandlerTest {

    @Test
    @DisplayName("验证任务被拒绝时计数器原子自增并委派底层原始 AbortPolicy 抛出异常")
    void testRejectedExecutionWithAbortPolicy() {
        ThreadPoolExecutor.AbortPolicy abortPolicy = new ThreadPoolExecutor.AbortPolicy();
        TrackingRejectedExecutionHandler handler = TrackingRejectedExecutionHandler.wrap(abortPolicy);

        assertThat(handler.getRejectedCount()).isEqualTo(0);
        assertThat(handler.getCumulativeRejectedCount()).isEqualTo(0);
        assertThat(handler.getDelegate()).isSameAs(abortPolicy);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                handler
        );

        try {
            Runnable task = () -> {};
            assertThatThrownBy(() -> handler.rejectedExecution(task, executor))
                    .isInstanceOf(RejectedExecutionException.class);

            assertThat(handler.getRejectedCount()).isEqualTo(1);
            assertThat(handler.getCumulativeRejectedCount()).isEqualTo(1);

            assertThatThrownBy(() -> handler.rejectedExecution(task, executor))
                    .isInstanceOf(RejectedExecutionException.class);

            assertThat(handler.getRejectedCount()).isEqualTo(2);
            assertThat(handler.getCumulativeRejectedCount()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("验证自定义委派 Handler 触发且计数正确")
    void testCustomDelegateHandler() {
        AtomicBoolean customHandlerCalled = new AtomicBoolean(false);
        TrackingRejectedExecutionHandler handler = new TrackingRejectedExecutionHandler((r, executor) -> {
            customHandlerCalled.set(true);
        });

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1)
        );

        try {
            handler.rejectedExecution(() -> {}, executor);
            assertThat(customHandlerCalled.get()).isTrue();
            assertThat(handler.getRejectedCount()).isEqualTo(1);
            assertThat(handler.getCumulativeRejectedCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("验证触发任务拒绝时广播发布 ThreadPoolRejectedEvent 且各字段信息完整")
    void testRejectedExecutionPublishesEvent() {
        List<ThreadPoolRejectedEvent> capturedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof ThreadPoolRejectedEvent rejectedEvent) {
                capturedEvents.add(rejectedEvent);
            }
        };

        ThreadPoolExecutor.DiscardPolicy discardPolicy = new ThreadPoolExecutor.DiscardPolicy();
        TrackingRejectedExecutionHandler handler = TrackingRejectedExecutionHandler.wrap(discardPolicy, "order-task-executor", publisher);

        assertThat(handler.getPoolName()).isEqualTo("order-task-executor");
        assertThat(handler.getEventPublisher()).isSameAs(publisher);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 10L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(5)
        );

        try {
            Runnable rejectedTask = new Runnable() {
                @Override
                public void run() {}

                @Override
                public String toString() {
                    return "OrderReconciliationTask#998";
                }
            };

            handler.rejectedExecution(rejectedTask, executor);

            assertThat(capturedEvents).hasSize(1);
            ThreadPoolRejectedEvent event = capturedEvents.get(0);

            assertThat(event.getPoolName()).isEqualTo("order-task-executor");
            assertThat(event.getPoolSize()).isEqualTo(executor.getPoolSize());
            assertThat(event.getCorePoolSize()).isEqualTo(2);
            assertThat(event.getMaxPoolSize()).isEqualTo(4);
            assertThat(event.getActiveCount()).isEqualTo(executor.getActiveCount());
            assertThat(event.getQueueSize()).isEqualTo(0);
            assertThat(event.getRemainingQueueCapacity()).isEqualTo(5);
            assertThat(event.getCumulativeRejectedCount()).isEqualTo(1);
            assertThat(event.getTaskDescription()).isEqualTo("OrderReconciliationTask#998");
            assertThat(event.getTimestamp()).isPositive();
            assertThat(event.toString()).contains("order-task-executor", "OrderReconciliationTask#998");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("验证事件发布异常时被安全拦截，不破坏底层委托拒绝策略执行（包含 Exception 与 Error）")
    void testRejectedExecutionWhenListenerThrowsException() {
        ApplicationEventPublisher failingPublisher = event -> {
            throw new AssertionError("下游监控事件总线模拟断言或链接错误");
        };

        AtomicBoolean delegateExecuted = new AtomicBoolean(false);
        TrackingRejectedExecutionHandler handler = new TrackingRejectedExecutionHandler(
                (r, executor) -> delegateExecuted.set(true),
                "biz-executor",
                failingPublisher
        );

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1)
        );

        try {
            assertThatCode(() -> handler.rejectedExecution(() -> {}, executor))
                    .doesNotThrowAnyException();

            assertThat(delegateExecuted.get()).isTrue();
            assertThat(handler.getRejectedCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("验证未配置事件发布器时正常执行拒绝策略且无异常")
    void testNullPublisherDoesNotThrow() {
        AtomicBoolean delegateExecuted = new AtomicBoolean(false);
        TrackingRejectedExecutionHandler handler = new TrackingRejectedExecutionHandler(
                (r, executor) -> delegateExecuted.set(true),
                "test-executor",
                null
        );

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1)
        );

        try {
            handler.rejectedExecution(() -> {}, executor);
            assertThat(delegateExecuted.get()).isTrue();
            assertThat(handler.getRejectedCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("验证 ThreadPoolRejectedEvent 便捷构造器参数绑定正确")
    void testThreadPoolRejectedEventConvenienceConstructor() {
        ThreadPoolRejectedEvent event = new ThreadPoolRejectedEvent(
                this, "simple-pool", 4, 3, 10, 5L
        );
        assertThat(event.getPoolName()).isEqualTo("simple-pool");
        assertThat(event.getPoolSize()).isEqualTo(4);
        assertThat(event.getActiveCount()).isEqualTo(3);
        assertThat(event.getQueueSize()).isEqualTo(10);
        assertThat(event.getCumulativeRejectedCount()).isEqualTo(5L);
        assertThat(event.getCorePoolSize()).isEqualTo(-1);
        assertThat(event.getMaxPoolSize()).isEqualTo(-1);
        assertThat(event.getRemainingQueueCapacity()).isEqualTo(-1);
        assertThat(event.getTaskDescription()).isNull();
    }

    @Test
    @DisplayName("验证重复 wrap 时幂等返回同一实例防多重嵌套，并能平滑补全 poolName 与 publisher")
    void testWrapIdempotency() {
        ThreadPoolExecutor.DiscardPolicy discardPolicy = new ThreadPoolExecutor.DiscardPolicy();
        TrackingRejectedExecutionHandler first = TrackingRejectedExecutionHandler.wrap(discardPolicy);
        assertThat(first.getPoolName()).isEqualTo("default");
        assertThat(first.getEventPublisher()).isNull();

        ApplicationEventPublisher publisher = event -> {};
        TrackingRejectedExecutionHandler second = TrackingRejectedExecutionHandler.wrap(first, "updatedPool", publisher);

        assertThat(second).isSameAs(first);
        assertThat(second.getPoolName()).isEqualTo("updatedPool");
        assertThat(second.getEventPublisher()).isSameAs(publisher);
    }
}
