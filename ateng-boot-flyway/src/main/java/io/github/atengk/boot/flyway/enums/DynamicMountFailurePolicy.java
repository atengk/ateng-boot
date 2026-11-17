package io.github.atengk.boot.flyway.enums;

/**
 * 动态挂载租户数据源自动迁移失败处置策略枚举
 *
 * @author Ateng
 * @since 2026-11-17
 */
public enum DynamicMountFailurePolicy {

    /**
     * 保留已挂载数据源并记录失败摘要、健康状态与失败事件，便于后续人工干预或重试补迁（默认）
     */
    KEEP_AND_REPORT,

    /**
     * 在同一生命周期锁临界区内自动摘除并关闭未完成建表的租户数据源，严防流量误入缺表租户库
     */
    ROLLBACK_DATASOURCE
}
