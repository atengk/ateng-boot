package io.github.atengk.boot.sample.mybatisplus.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 行级多租户自动隔离与跨租户平台汇总对比视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class TenantIsolationCompareVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前绑定的租户标识
     */
    private String currentTenantId;

    /**
     * 当前租户隔离视图可见订单数
     */
    private int tenantScopedCount;

    /**
     * 通过 @IgnoreTenant 注解查询到的跨租户总订单数
     */
    private int ignoreTenantAnnotationCount;

    /**
     * 通过 TenantContextHolder.runWithoutTenant 闭包查询到的跨租户总订单数
     */
    private int runWithoutTenantClosureCount;

    /**
     * 当前租户隔离视图订单列表
     */
    private List<TenantOrderVO> tenantScopedOrders;

    /**
     * 跨租户全平台汇总订单列表
     */
    private List<TenantOrderVO> globalOrders;
}
