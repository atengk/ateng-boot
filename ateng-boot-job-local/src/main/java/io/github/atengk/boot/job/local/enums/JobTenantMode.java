package io.github.atengk.boot.job.local.enums;

/**
 * SaaS 多租户定时任务调度模态枚举
 *
 * @author Ateng
 * @since 2026-11-01
 */
public enum JobTenantMode {

    /**
     * 忽略租户（默认模式）
     * <p>
     * 以系统全局视角执行任务，不对 TenantContext 施加特定租户上下文或标记忽略租户，
     * 适用于系统级垃圾回收、跨租户全量汇总或纯基础设施健康检测。
     * </p>
     */
    IGNORE,

    /**
     * 参数指定单租户
     * <p>
     * 调度触发时从入参 params 中提取 {@code tenantId} 并注入上下文，仅对该指定租户执行一次。
     * 适用于指定租户重算、运营手工触发单租户任务等场景。
     * </p>
     */
    PARAM,

    /**
     * 全租户轮询遍历
     * <p>
     * 调度触发时，自动通过 {@link io.github.atengk.boot.job.local.tenant.TenantProvider} 检索系统中所有有效租户，
     * 依次切换 {@code TenantContextHolder} 上下文顺序执行。
     * 具备 Fail-Safe 容错防卫：单租户执行异常不阻断后续正常租户，并在最终 {@code JobResult} 聚合全量统计。
     * </p>
     */
    ITERATE
}
