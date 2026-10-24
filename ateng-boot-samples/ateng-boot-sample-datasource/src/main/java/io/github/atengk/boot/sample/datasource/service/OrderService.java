package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
import io.github.atengk.boot.sample.datasource.vo.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单核心业务服务
 * <p>
 * 演示多租户物理分库、读写分离以及写后读主防延迟。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final ReadAfterWriteManager readAfterWriteManager;

    /**
     * 租户透明切库下单
     * <p>
     * 自动感知 TenantContextHolder.getTenantId() 并路由至对应租户数据库。
     *
     * @param request 订单创建请求
     * @return 订单展示视图对象
     */
    public OrderResponse createTenantOrder(OrderCreateRequest request) {
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[OrderService] 开始为租户 [{}] 创建订单: {}", currentTenant, request.getOrderNo());

        OrderEntity entity = new OrderEntity();
        entity.setOrderNo(request.getOrderNo());
        entity.setProductName(request.getProductName());
        entity.setAmount(request.getAmount());
        entity.setTenantId(currentTenant);
        entity.setStatus("CREATED");

        orderMapper.insert(entity);

        return convertToVo(entity, dynamicDatasourceTemplate.getCurrentDataSourceKey());
    }

    /**
     * 租户透明查询订单
     *
     * @param orderNo 业务订单号
     * @return 订单展示视图对象
     */
    public OrderResponse getTenantOrder(String orderNo) {
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[OrderService] 查询租户 [{}] 订单: {}", currentTenant, orderNo);

        OrderEntity entity = orderMapper.selectOne(
                new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getOrderNo, orderNo)
        );

        if (entity == null) {
            return null;
        }
        return convertToVo(entity, dynamicDatasourceTemplate.getCurrentDataSourceKey());
    }

    /**
     * 主库写入订单 (在写事务中自动绑定 master 并标记写后读主)
     *
     * @param request 订单创建请求
     * @return 订单展示视图对象
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse createMasterOrderWithPostWrite(OrderCreateRequest request) {
        log.info("[OrderService] 主库事务写入订单并激活写后读主防延迟标记: {}", request.getOrderNo());
        readAfterWriteManager.markWriteOccurred();

        OrderEntity entity = new OrderEntity();
        entity.setOrderNo(request.getOrderNo());
        entity.setProductName(request.getProductName());
        entity.setAmount(request.getAmount());
        entity.setTenantId(TenantContextHolder.getTenantId());
        entity.setStatus("PAID");

        orderMapper.insert(entity);

        return convertToVo(entity, dynamicDatasourceTemplate.getCurrentDataSourceKey());
    }

    /**
     * 读从库订单 (标记 @DS("slave"))
     * <p>
     * 默认路由至 slave 读从库；若在写后读主防延迟窗口内，将自动回退路由至 master。
     *
     * @param orderNo 业务订单号
     * @return 订单展示视图对象
     */
    @DS("slave")
    public OrderResponse getOrderReadWrite(String orderNo) {
        log.info("[OrderService] 从库读取订单 (受写后读主防延迟保护): {}", orderNo);

        OrderEntity entity = orderMapper.selectOne(
                new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getOrderNo, orderNo)
        );

        if (entity == null) {
            return null;
        }
        return convertToVo(entity, dynamicDatasourceTemplate.getCurrentDataSourceKey());
    }

    /**
     * 显式从库注解查询 (@DS("slave_1"))
     *
     * @param orderNo 业务订单号
     * @return 订单展示视图对象
     */
    @DS("slave_1")
    public OrderResponse getOrderFromExplicitSlave(String orderNo) {
        OrderEntity entity = orderMapper.selectOne(
                new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getOrderNo, orderNo)
        );
        if (entity == null) {
            return null;
        }
        return convertToVo(entity, dynamicDatasourceTemplate.getCurrentDataSourceKey());
    }

    /**
     * 持久层实体转换为出参视图对象 (契约绝对解耦)
     */
    private OrderResponse convertToVo(OrderEntity entity, String routedDs) {
        return OrderResponse.builder()
                .id(entity.getId())
                .orderNo(entity.getOrderNo())
                .tenantId(entity.getTenantId())
                .productName(entity.getProductName())
                .amount(entity.getAmount())
                .status(entity.getStatus())
                .routedDataSource(routedDs)
                .build();
    }
}
