package io.github.atengk.boot.job.snail.tenant;

import java.util.List;

/**
 * SnailJob 多租户轮询租户列表提供者 SPI
 *
 * <p>供业务系统实现并注册为 Spring Bean，用于在 {@link io.github.atengk.boot.job.snail.enums.JobTenantMode#ITERATE} 模式
 * 或使用 {@link io.github.atengk.boot.job.snail.template.TenantJobTemplate} 时提供需要遍历的有效租户标识列表。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@FunctionalInterface
public interface TenantListProvider {

    /**
     * 获取所有需要执行调度任务的有效租户标识列表
     *
     * @return 租户标识列表，无租户时返回空列表（严禁返回 null）
     */
    List<String> getTenantIds();

}
