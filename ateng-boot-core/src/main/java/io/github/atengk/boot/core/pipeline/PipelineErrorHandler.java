package io.github.atengk.boot.core.pipeline;

/**
 * 管道阶段异常拦截与自定义错误处理回调
 *
 * @param <C> 管道上下文类型
 * @author Ateng
 * @since 2026-10-27
 */
@FunctionalInterface
public interface PipelineErrorHandler<C> {

    /**
     * 当某阶段抛出未捕获异常时触发的回调处理
     *
     * @param stage   发生异常的阶段
     * @param context 当前流转的上下文
     * @param cause   捕获到的异常对象
     */
    void handleError(PipelineStage<C> stage, C context, Throwable cause);
}
