package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.ds.GroupDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租户级从库分组路由与写后读主防延迟场景服务
 * <p>
 * 演示租户多从库分组路由（GroupDataSource）与写后读主防从库主从同步延迟闭环。
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceTenantReadWriteScenarioService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final AtengDynamicRoutingDataSource routingDataSource;
    private final ReadAfterWriteManager readAfterWriteManager;
    private final OrderMapper orderMapper;

    /**
     * 为指定租户配置多节点读从库分组 (GroupDataSource)
     *
     * @param tenantId    租户标识
     * @param groupKey    从库分组标识（如 tenant_1001_slave）
     * @param nodeKey     从库物理节点标识（如 slave）
     */
    public void configureTenantSlaveGroup(String tenantId, String groupKey, String nodeKey) {
        log.info("[TenantReadWriteScenario] 为租户 [{}] 配置只读从库组 [groupKey={}, nodeKey={}]", tenantId, groupKey, nodeKey);
        DataSource nodeDs = routingDataSource.findDataSource(nodeKey);
        if (nodeDs != null) {
            GroupDataSource groupDataSource = new GroupDataSource(groupKey, routingDataSource.getHealthAwareStrategy());
            groupDataSource.addDatasource(nodeKey, nodeDs);
            routingDataSource.getGroupDataSources().put(groupKey, groupDataSource);
            log.info("[TenantReadWriteScenario] 租户从库组挂载完成: {}", groupKey);
        }
    }

    /**
     * 租户下单写入并感知写操作发生（触发写后读主防延迟窗口）
     *
     * @param tenantId 租户标识
     * @param orderNo  订单号
     */
    public void createOrderWithWriteTracking(String tenantId, String orderNo) {
        log.info("[TenantReadWriteScenario] 租户 [{}] 写入订单并标记写发生: {}", tenantId, orderNo);
        dynamicDatasourceTemplate.callInTenantDataSource(tenantId, () -> {
            OrderEntity order = new OrderEntity();
            order.setOrderNo(orderNo);
            order.setTenantId(tenantId);
            order.setProductName("Tenant RW Product");
            order.setAmount(new BigDecimal("188.00"));
            order.setStatus("CREATED");
            orderMapper.insert(order);

            // 触发写操作标记
            if (readAfterWriteManager != null) {
                readAfterWriteManager.markWriteOccurred();
            }
            return order;
        });
    }

    /**
     * 具备写后读主一致性保障的租户只读查询
     *
     * @param tenantId 租户标识
     * @param orderNo  订单号
     * @return 查询到的订单实体
     */
    public OrderEntity queryOrderWithConsistency(String tenantId, String orderNo) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            // 在只读从库上下文查询
            String slaveGroup = "tenant_" + tenantId + "_slave";
            DynamicDataSourceContextHolder.push(slaveGroup);
            try {
                // 底层 AtengDynamicRoutingDataSource 将自动判断写后读主窗口
                // 若处于写防延迟窗口内，强制自适应路由至租户主库查询最新数据
                return orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                        .eq(OrderEntity::getOrderNo, orderNo));
            } finally {
                DynamicDataSourceContextHolder.poll();
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * 判断当前写后读主防延迟标记是否处于激活生效中
     *
     * @return true 表示处于写后读主保护窗口
     */
    public boolean isWriteOccurredActive() {
        return readAfterWriteManager != null && readAfterWriteManager.isWriteOccurred();
    }
}
