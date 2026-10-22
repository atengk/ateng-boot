package io.github.atengk.boot.observability.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TrackingRejectedExecutionHandler 任务拒绝追踪装饰器单元测试
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

            assertThatThrownBy(() -> handler.rejectedExecution(task, executor))
                    .isInstanceOf(RejectedExecutionException.class);

            assertThat(handler.getRejectedCount()).isEqualTo(2);
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
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("验证重复 wrap 时幂等返回同一实例防多重嵌套")
    void testWrapIdempotency() {
        ThreadPoolExecutor.DiscardPolicy discardPolicy = new ThreadPoolExecutor.DiscardPolicy();
        TrackingRejectedExecutionHandler first = TrackingRejectedExecutionHandler.wrap(discardPolicy);
        TrackingRejectedExecutionHandler second = TrackingRejectedExecutionHandler.wrap(first);

        assertThat(second).isSameAs(first);
    }
}
