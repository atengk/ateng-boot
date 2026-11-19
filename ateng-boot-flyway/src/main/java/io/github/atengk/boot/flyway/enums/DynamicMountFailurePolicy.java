package io.github.atengk.boot.flyway.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 动态挂载租户数据源自动迁移失败处置策略枚举
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@AllArgsConstructor
public enum DynamicMountFailurePolicy implements IBaseEnum<String> {

    /**
     * 保留已挂载数据源并记录失败摘要、健康状态与失败事件，便于后续人工干预或重试补迁（默认）
     */
    KEEP_AND_REPORT("KEEP_AND_REPORT", "保留数据源并上报"),

    /**
     * 在同一生命周期锁临界区内自动摘除并关闭未完成建表的租户数据源，严防流量误入缺表租户库
     */
    ROLLBACK_DATASOURCE("ROLLBACK_DATASOURCE", "自动摘除回滚数据源");

    private final String code;
    private final String label;
}
