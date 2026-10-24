package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 事务内多数据源安全防卫场景服务
 * <p>
 * 演示在 @Transactional 事务中尝试跨物理连接切源时，被 DynamicDatasourceGuard 刚性拦截，
 * 以及采用 Propagation.REQUIRES_NEW 挂起外层事务安全开启独立新事务。
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Slf4j
@Service
public class DatasourceTransactionGuardScenarioService {

    private final OrderMapper orderMapper;
    private final DatasourceTransactionGuardScenarioService self;

    public DatasourceTransactionGuardScenarioService(OrderMapper orderMapper,
                                                     @Lazy DatasourceTransactionGuardScenarioService self) {
        this.orderMapper = orderMapper;
        this.self = self;
    }

    /**
     * 在事务内违规切源 (将触发 DynamicDatasourceGuard 刚性拦截)
     *
     * @param masterOrderNo 主库订单号
     * @param tenantOrderNo 租户订单号
     */
    @Transactional(rollbackFor = Exception.class)
    @DS("master")
    public void transactionWithIllegalSwitch(String masterOrderNo, String tenantOrderNo) {
        log.info("[TxGuardScenario] 正在主库事务内插入订单: {}", masterOrderNo);
        OrderEntity masterOrder = new OrderEntity();
        masterOrder.setOrderNo(masterOrderNo);
        masterOrder.setProductName("Master Tx Product");
        masterOrder.setAmount(new BigDecimal("100.00"));
        masterOrder.setStatus("TX_PENDING");
        orderMapper.insert(masterOrder);

        log.info("[TxGuardScenario] 尝试在当前事务中违规切换到 tenant_1001 库，预期被 DynamicDatasourceGuard 拦截");
        // 调用自身切源方法，此时处于 master 事务中，切换到 tenant_1001 将触发刚性阻断
        self.insertInTenantDs(tenantOrderNo);
    }

    /**
     * 在同一物理连接的数据源内重入切源 (允许正常执行)
     *
     * @param orderNo 订单号
     */
    @Transactional(rollbackFor = Exception.class)
    @DS("master")
    public void transactionWithSameDsReentrant(String orderNo) {
        log.info("[TxGuardScenario] 主库事务内同源重入切源: {}", orderNo);
        self.insertInMasterDs(orderNo);
    }

    /**
     * 通过 REQUIRES_NEW 挂起主事务安全切换数据源
     *
     * @param masterOrderNo 主库订单号
     * @param tenantOrderNo 租户库订单号
     */
    @Transactional(rollbackFor = Exception.class)
    @DS("master")
    public void transactionWithSafeRequiresNew(String masterOrderNo, String tenantOrderNo) {
        log.info("[TxGuardScenario] 主事务中插入 master 订单: {}", masterOrderNo);
        OrderEntity masterOrder = new OrderEntity();
        masterOrder.setOrderNo(masterOrderNo);
        masterOrder.setProductName("Master Outer Tx");
        masterOrder.setAmount(new BigDecimal("200.00"));
        masterOrder.setStatus("OUTER_TX");
        orderMapper.insert(masterOrder);

        // 挂起外层事务并在新事务中切换到 tenant_1001
        self.insertInTenantWithRequiresNew(tenantOrderNo);
    }

    @DS("tenant_1001")
    public void insertInTenantDs(String orderNo) {
        OrderEntity tenantOrder = new OrderEntity();
        tenantOrder.setOrderNo(orderNo);
        tenantOrder.setProductName("Tenant Tx Product");
        tenantOrder.setAmount(new BigDecimal("50.00"));
        tenantOrder.setStatus("TENANT_TX");
        orderMapper.insert(tenantOrder);
    }

    @DS("master")
    public void insertInMasterDs(String orderNo) {
        OrderEntity masterOrder = new OrderEntity();
        masterOrder.setOrderNo(orderNo);
        masterOrder.setProductName("Master Reentrant");
        masterOrder.setAmount(new BigDecimal("80.00"));
        masterOrder.setStatus("REENTRANT_TX");
        orderMapper.insert(masterOrder);
    }

    @DS("tenant_1001")
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void insertInTenantWithRequiresNew(String orderNo) {
        log.info("[TxGuardScenario] REQUIRES_NEW 独立新事务中安全切换至 tenant_1001 执行插入: {}", orderNo);
        OrderEntity tenantOrder = new OrderEntity();
        tenantOrder.setOrderNo(orderNo);
        tenantOrder.setProductName("Tenant Independent Tx");
        tenantOrder.setAmount(new BigDecimal("150.00"));
        tenantOrder.setStatus("REQUIRES_NEW_OK");
        orderMapper.insert(tenantOrder);
    }
}
