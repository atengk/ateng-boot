package io.github.atengk.boot.sample.service.impl;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mapper.TenantOrderMapper;
import io.github.atengk.boot.sample.service.TenantOrderService;
import io.github.atengk.boot.sample.vo.TenantOrderVO;
import org.springframework.stereotype.Service;

/**
 * 多租户订单业务实现
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Service
public class TenantOrderServiceImpl extends AtengServiceImpl<TenantOrderMapper, TenantOrderEntity> implements TenantOrderService {

    @Override
    public PageResult<TenantOrderVO> pageTenantOrders(DynamicQueryRequest request) {
        return pageDynamic(request, TenantOrderVO.class);
    }
}
