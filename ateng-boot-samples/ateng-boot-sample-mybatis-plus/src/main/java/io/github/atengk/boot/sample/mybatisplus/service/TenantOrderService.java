package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mybatisplus.model.TenantIsolationCompareVO;
import io.github.atengk.boot.sample.mybatisplus.model.TenantOrderVO;

import java.util.List;

/**
 * 租户订单业务服务契约
 * 继承 AtengBaseService 统一获取批量写入、动态查询、受管流式查询与乐观锁并发自旋重试能力
 *
 * @author Ateng
 * @since 2026-10-19
 */
public interface TenantOrderService extends AtengBaseService<TenantOrderEntity> {

    /**
     * 在指定租户上下文下查询可见订单列表（若表为空按需初始化多租户演示种子数据）
     *
     * @param tenantId 租户标识
     * @return 当前租户可见的订单视图列表
     */
    List<TenantOrderVO> listTenantOrders(String tenantId);

    /**
     * 对比验证行级多租户自动隔离与 @IgnoreTenant 注解 / runWithoutTenant 闭包跨租户平台汇总
     *
     * @param currentTenantId 当前绑定的租户标识
     * @return 多租户隔离与跨租户汇总对比视图
     */
    TenantIsolationCompareVO compareTenantIsolationAndBypass(String currentTenantId);
}
