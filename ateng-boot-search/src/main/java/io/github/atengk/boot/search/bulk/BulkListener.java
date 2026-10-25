package io.github.atengk.boot.search.bulk;

import io.github.atengk.boot.search.model.SearchBulkResult;

/**
 * 批量缓冲处理器生命周期监听器
 * <p>
 * 提供批次执行前、执行成功（含局部宽容结果）以及执行异常的钩子回调。
 *
 * @author Ateng
 * @since 2026-10-25
 */
public interface BulkListener {

    /**
     * 默认无操作空监听器实现
     */
    BulkListener NOOP = new BulkListener() {};

    /**
     * 在批量请求物理发送前触发
     *
     * @param executionId     请求批次全局唯一单调递增 ID
     * @param numberOfActions 本批次包含的有效操作项总数
     */
    default void beforeBulk(long executionId, int numberOfActions) {
    }

    /**
     * 在批量请求物理完成（含宽容模式下局部成功）后触发
     *
     * @param executionId     请求批次全局唯一单调递增 ID
     * @param numberOfActions 本批次包含的有效操作项总数
     * @param result          批量操作结果快照（含成功数、失败数、耗时及失败明细）
     */
    default void afterBulk(long executionId, int numberOfActions, SearchBulkResult result) {
    }

    /**
     * 在批量请求发生严重不可恢复异常（如网络中断、认证失败、集群熔断）后触发
     *
     * @param executionId     请求批次全局唯一单调递增 ID
     * @param numberOfActions 本批次包含的有效操作项总数
     * @param failure         底层异常根因
     */
    default void afterBulk(long executionId, int numberOfActions, Throwable failure) {
    }
}
