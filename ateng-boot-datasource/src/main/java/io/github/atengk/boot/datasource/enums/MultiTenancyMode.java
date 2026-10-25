package io.github.atengk.boot.datasource.enums;

/**
 * 物理分库多租户隔离模式枚举
 *
 * @author Ateng
 * @since 2026-10-24
 */
public enum MultiTenancyMode {

    /**
     * 严格物理分库模式：未找到租户独立物理库时，立即抛出 TenantDataSourceNotFoundException 快速失败阻断
     */
    STRICT,

    /**
     * 混合租户模式（默认）：未找到独立物理库时，自动平滑回退至主数据源（master），交由 MyBatis-Plus 行级租户插件进行逻辑隔离
     */
    FALLBACK_MASTER,

    /**
     * 免租户直通模式：忽略租户上下文路由规则，统一路由至主数据源（master）
     */
    IGNORE
}
