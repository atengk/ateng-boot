package io.github.atengk.boot.flyway.enums;

/**
 * 迁移目标数据源分类枚举
 *
 * @author Ateng
 * @since 2026-11-17
 */
public enum MigrationTargetKind {

    /**
     * 主数据源（L1 单库默认源或主从读写分离物理主库 master）
     */
    PRIMARY,

    /**
     * 显式纳管的静态业务独立数据源（L2，如 log_db）
     */
    STATIC_DATASOURCE,

    /**
     * SaaS 物理租户分库（L3，如 tenant_1001）
     */
    TENANT,

    /**
     * 通过自定义 {@code FlywayTargetResolver} 注册的扩展目标
     */
    CUSTOM
}
