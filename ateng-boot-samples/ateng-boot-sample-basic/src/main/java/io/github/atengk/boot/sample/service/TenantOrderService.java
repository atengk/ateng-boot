package io.github.atengk.boot.sample.service;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.vo.TenantOrderVO;

/**
 * 多租户订单业务接口
 * 继承 AtengBaseService 获得动态查询条件无感直出能力，为控制层提供业务边界防腐
 *
 * @author Ateng
 * @since 2026-10-19
 */
public interface TenantOrderService extends AtengBaseService<TenantOrderEntity> {

    /**
     * 结合多租户行级隔离动态分页查询订单列表并直出 VO
     *
     * @param request 动态查询请求契约
     * @return 订单分页视图结果
     */
    PageResult<TenantOrderVO> pageTenantOrders(DynamicQueryRequest request);
}
