package io.github.atengk.boot.job.xxl.template;

import java.util.function.Consumer;

/**
 * 分片流式批处理失败切片函数式回调 SPI
 *
 * <p>在大数据量分片游标批处理或 MyBatis 流式游标批处理发生单批消费异常时触发，
 * 接收封装了失败记录明细、批次序号、游标位置与底层异常原因的 {@link FailedBatch}，
 * 供业务方执行死信落库、告警通知或补偿派发。</p>
 *
 * <p>继承自 {@link Consumer} 并通过 {@code default accept} 委托至 {@link #onFailure(FailedBatch)}，
 * 既支持作为 Spring 全局默认 {@code BatchFailureHandler} Bean 自动装配，也 100% 兼容既有匿名 Lambda 与方法引用。</p>
 *
 * @param <T> 失败切片中的实体类型
 * @author Ateng
 * @since 2026-11-19
 */
@FunctionalInterface
public interface BatchFailureHandler<T> extends Consumer<FailedBatch<T>> {

    /**
     * 处理发生异常的单个批处理切片
     *
     * @param failedBatch 失败切片死信封装模型（包含 {@code items}、{@code batchIndex}、{@code cursorId}、{@code cause}）
     */
    void onFailure(FailedBatch<T> failedBatch);

    /**
     * 兼容标准 {@link Consumer} 契约的默认委托入口
     *
     * @param failedBatch 失败切片死信封装模型
     */
    @Override
    default void accept(FailedBatch<T> failedBatch) {
        onFailure(failedBatch);
    }
}
