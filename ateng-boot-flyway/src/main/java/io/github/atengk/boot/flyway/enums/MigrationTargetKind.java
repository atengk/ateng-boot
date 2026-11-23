package io.github.atengk.boot.flyway.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 迁移目标数据源分类枚举
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@AllArgsConstructor
public enum MigrationTargetKind implements IBaseEnum<String> {

    /**
     * 主数据源（L1 单库默认源或主从读写分离物理主库 master）
     */
    PRIMARY("PRIMARY", "主数据源"),

    /**
     * 显式纳管的静态业务独立数据源（L2，如 log_db）
     */
    STATIC_DATASOURCE("STATIC_DATASOURCE", "静态业务数据源"),

    /**
     * SaaS 物理租户分库（L3，如 tenant_1001）
     */
    TENANT("TENANT", "SaaS物理租户数据源"),

    /**
     * 通过自定义 {@code FlywayTargetResolver} 注册的扩展目标
     */
    CUSTOM("CUSTOM", "自定义扩展数据源");

    private final String code;
    private final String label;
}
