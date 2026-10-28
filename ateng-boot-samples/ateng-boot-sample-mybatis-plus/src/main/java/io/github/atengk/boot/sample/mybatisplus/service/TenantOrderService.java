package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantOrderEntity;

/**
 * 租户订单业务服务契约
 * 继承 AtengBaseService 统一获取批量写入、动态查询、受管流式查询与乐观锁并发自旋重试能力
 *
 * @author Ateng
 * @since 2026-10-19
 */
public interface TenantOrderService extends AtengBaseService<TenantOrderEntity> {
}
