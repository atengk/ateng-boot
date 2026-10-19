package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.TenantOrderMapper;
import io.github.atengk.boot.sample.mybatisplus.service.TenantOrderService;
import org.springframework.stereotype.Service;

/**
 * 租户订单业务服务实现
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Service
public class TenantOrderServiceImpl extends AtengServiceImpl<TenantOrderMapper, TenantOrderEntity> implements TenantOrderService {
}
