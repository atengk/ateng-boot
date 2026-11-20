package io.github.atengk.boot.job.snail.template;

import java.util.function.Consumer;

/**
 * 分片流式与多租户批处理失败切片函数式回调 SPI
 *
 * <p>在大数据量分片游标批处理、MyBatis 流式游标批处理或多租户并发执行发生单批/单租户异常时触发，
 * 接收封装了游标范围、分片索引、批次序号、条数与底层异常原因的 {@link FailedBatch}，
 * 供业务方执行死信落库、告警通知或补偿派发。</p>
 *
 * <p>继承自 {@link Consumer} 并通过 {@code default accept} 委托至 {@link #onFailure(FailedBatch)}，
 * 既支持作为 Spring 全局默认 {@code BatchFailureHandler} Bean 自动装配，也 100% 兼容既有 Lambda 与方法引用；
 * 当调用方显式传入局部回调时，优先触发显式回调，全局默认 Bean 不重复触发。</p>
 *
 * @param <K> 失败切片或游标/租户标识泛型
 * @author Ateng
 * @since 2026-11-20
 */
@FunctionalInterface
public interface BatchFailureHandler<K> extends Consumer<FailedBatch<K>> {

    /**
     * 处理发生异常的单个批处理切片或租户执行单元
     *
     * @param failedBatch 失败切片上下文封装模型
     */
    void onFailure(FailedBatch<K> failedBatch);

    /**
     * 兼容标准 {@link Consumer} 契约的默认委托入口
     *
     * @param failedBatch 失败切片上下文封装模型
     */
    @Override
    default void accept(FailedBatch<K> failedBatch) {
        onFailure(failedBatch);
    }
}
