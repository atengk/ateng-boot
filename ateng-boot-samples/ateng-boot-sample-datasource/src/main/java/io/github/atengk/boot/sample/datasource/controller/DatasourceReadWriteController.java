package io.github.atengk.boot.sample.datasource.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.service.DatasourceActiveProbingScenarioService;
import io.github.atengk.boot.sample.datasource.service.DatasourceTenantReadWriteScenarioService;
import io.github.atengk.boot.sample.datasource.service.OrderService;
import io.github.atengk.boot.sample.datasource.vo.OrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 主从读写分离与自适应探活自愈控制器
 * <p>
 * 提供主写从读路由、写后读主防从库主从延迟、租户多从库分组集群分发以及从库故障熔断主动探活自愈演示。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/datasource/read-write")
@RequiredArgsConstructor
public class DatasourceReadWriteController {

    private final OrderService orderService;
    private final DatasourceTenantReadWriteScenarioService tenantReadWriteScenarioService;
    private final DatasourceActiveProbingScenarioService activeProbingScenarioService;

    /**
     * 1. 主库事务写入订单并激活写后读主防延迟标记
     *
     * @param request 订单创建请求
     * @return 订单展示强类型结果
     */
    @PostMapping("/order/master")
    public Result<OrderResponse> createMasterOrder(@Valid @RequestBody OrderCreateRequest request) {
        OrderResponse response = orderService.createMasterOrderWithPostWrite(request);
        return Result.success(response);
    }

    /**
     * 2. 从库只读查询订单 (在写后读主保护期内自动自适应回退到 master 读取)
     *
     * @param orderNo 业务订单号
     * @return 订单展示强类型结果
     */
    @GetMapping("/order/get/{orderNo}")
    public Result<OrderResponse> getOrderReadWrite(@PathVariable String orderNo) {
        OrderResponse response = orderService.getOrderReadWrite(orderNo);
        return Result.success(response);
    }

    /**
     * 3. 租户级读从库分组查询 (GroupDataSource 路由)
     *
     * @param orderNo  业务订单号
     * @param tenantId 租户标识 (默认 1001)
     * @return 订单展示强类型结果
     */
    @GetMapping("/tenant-group/order/{orderNo}")
    public Result<OrderResponse> getTenantGroupOrder(@PathVariable String orderNo,
                                                     @RequestParam(defaultValue = "1001") String tenantId) {
        OrderEntity entity = tenantReadWriteScenarioService.queryOrderWithConsistency(tenantId, orderNo);
        if (entity == null) {
            return Result.success(null);
        }
        OrderResponse response = OrderResponse.builder()
                .id(entity.getId())
                .orderNo(entity.getOrderNo())
                .tenantId(entity.getTenantId())
                .productName(entity.getProductName())
                .amount(entity.getAmount())
                .status(entity.getStatus())
                .routedDataSource("tenant_" + tenantId + "_slave")
                .build();
        return Result.success(response);
    }

    /**
     * 4. 模拟从库物理节点连接失败并触发熔断隔离 (加入黑名单)
     *
     * @param replicaKey 从库标识 (默认 slave)
     * @return 操作成功结果
     */
    @PostMapping("/failover/simulate-failure")
    public Result<Boolean> simulateReplicaFailure(@RequestParam(defaultValue = "slave") String replicaKey) {
        activeProbingScenarioService.simulateReplicaFailure(replicaKey);
        return Result.success(true);
    }

    /**
     * 5. 查询当前所有处于熔断黑名单中的从库物理节点
     *
     * @return 熔断节点集合
     */
    @GetMapping("/failover/blacklisted")
    public Result<Set<String>> getBlacklistedReplicas() {
        Set<String> blacklisted = activeProbingScenarioService.getBlacklistedDataSources();
        return Result.success(blacklisted);
    }

    /**
     * 6. 手动将从库节点移出黑名单 (模拟物理节点自愈恢复)
     *
     * @param replicaKey 从库标识 (默认 slave)
     * @return 操作成功结果
     */
    @PostMapping("/failover/recover")
    public Result<Boolean> recoverReplica(@RequestParam(defaultValue = "slave") String replicaKey) {
        activeProbingScenarioService.recoverReplica(replicaKey);
        return Result.success(true);
    }
}
