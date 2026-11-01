package io.github.atengk.boot.job.local.tenant;

import java.util.List;

/**
 * SaaS 多租户发现服务 SPI
 * <p>
 * 当定时任务配置为 {@link io.github.atengk.boot.job.local.enums.JobTenantMode#ITERATE} 全租户轮询模态时，
 * 调度引擎通过本接口获取系统中当前活跃的有效租户标识列表以实施遍历执行。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@FunctionalInterface
public interface TenantProvider {

    /**
     * 获取系统中所有有效租户标识集合
     *
     * @return 租户标识列表（必须非 null，若无有效租户返回空列表）
     */
    List<String> getTenantIds();
}
