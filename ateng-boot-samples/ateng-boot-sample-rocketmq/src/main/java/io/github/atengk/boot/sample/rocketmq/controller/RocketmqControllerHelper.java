package io.github.atengk.boot.sample.rocketmq.controller;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RocketMQ Sample 控制器异步等待共享辅助工具
 *
 * <p>统一收敛各演示控制器对 {@link CompletableFuture} 的有界等待、中断标志恢复与异常解包逻辑。</p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
final class RocketmqControllerHelper {

    private RocketmqControllerHelper() {
    }

    /**
     * 在限定超时时间内等待异步发信完成，并在中断时恢复线程中断状态、透传底层运行时异常
     *
     * @param future         异步结果 Future
     * @param timeout        最大等待时长
     * @param operationLabel 操作语义描述（用于异常上下文提示）
     * @param <T>            结果类型
     * @return 异步计算结果
     */
    static <T> T awaitFuture(CompletableFuture<T> future, Duration timeout, String operationLabel) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待" + operationLabel + "结果被中断", ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("等待" + operationLabel + "结果超时", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(operationLabel + "失败", cause != null ? cause : ex);
        }
    }
}
