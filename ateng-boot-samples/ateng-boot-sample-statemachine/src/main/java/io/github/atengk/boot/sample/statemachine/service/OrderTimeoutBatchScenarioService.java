package io.github.atengk.boot.sample.statemachine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.mapper.OrderMapper;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.BatchOptions;
import io.github.atengk.boot.statemachine.model.BatchTransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 超时订单大批量切片关单场景服务
 * <p>演示 fireEventBatch 切片流转引擎、结构化聚合结果与部分成功容错治理</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
public class OrderTimeoutBatchScenarioService {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutBatchScenarioService.class);

    private final OrderMapper orderMapper;
    private final StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;
    private final OrderLifecycleScenarioService lifecycleService;

    public OrderTimeoutBatchScenarioService(OrderMapper orderMapper,
                                            StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine) {
        this(orderMapper, orderStateMachine, null);
    }

    @Autowired
    public OrderTimeoutBatchScenarioService(OrderMapper orderMapper,
                                            StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine,
                                            @Lazy OrderLifecycleScenarioService lifecycleService) {
        this.orderMapper = orderMapper;
        this.orderStateMachine = orderStateMachine;
        this.lifecycleService = lifecycleService;
    }

    /**
     * 自动扫描待处理（待支付、已支付、待发货）的订单 ID 列表
     *
     * @param limit 扫描上限
     * @return 订单 ID 列表
     */
    public List<Long> scanPendingOrderIds(int limit) {
        int max = limit > 0 ? limit : 100;
        List<OrderDO> pendingOrders = orderMapper.selectList(new LambdaQueryWrapper<OrderDO>()
                .in(OrderDO::getStatus, OrderState.WAIT_PAY, OrderState.PAID, OrderState.WAIT_DELIVER)
                .last("LIMIT " + max));
        if (pendingOrders == null || pendingOrders.isEmpty()) {
            return Collections.emptyList();
        }
        return pendingOrders.stream().map(OrderDO::getId).collect(Collectors.toList());
    }

    /**
     * 大批量自动取消超时未支付/待发货订单
     *
     * @param orderIds     目标订单 ID 集合
     * @param cancelReason 取消原因说明
     * @param batchSize    单批切片批次大小（如 10 或 50）
     * @return 结构化批量流转聚合结果对象
     */
    public BatchTransitionResult<OrderState, OrderDO> batchCancelExpiredOrders(List<Long> orderIds,
                                                                               String cancelReason,
                                                                               int batchSize) {
        if (orderIds == null || orderIds.isEmpty()) {
            return BatchTransitionResult.empty();
        }

        List<OrderDO> orders = orderMapper.selectBatchIds(orderIds);
        if (orders == null || orders.isEmpty()) {
            return BatchTransitionResult.empty();
        }

        BatchOptions options = BatchOptions.of(batchSize > 0 ? batchSize : 50, false);

        OrderContext context = OrderContext.builder()
                .trackingRemark(cancelReason != null ? cancelReason : "超时未支付系统自动关单")
                .operator("BATCH_TIMEOUT_JOB")
                .build();

        log.info("[BatchCancel] 开始切片批量取消超时订单: 待处理总数={}, 批次大小={}", orders.size(), options.getBatchSize());

        BatchTransitionResult<OrderState, OrderDO> result = orderStateMachine.fireEventBatch(
                orders,
                OrderDO::getStatus,
                (order, targetState) -> {
                    OrderState currentSource = order.getStatus();
                    int updated = orderMapper.updateStatusWithCas(
                            order.getId(),
                            currentSource.name(),
                            targetState.name(),
                            order.getVersion()
                    );
                    if (updated == 0) {
                        throw new StateMachineException(
                                StateMachineResultCode.CONCURRENT_CAS_CONFLICT,
                                currentSource,
                                targetState,
                                OrderEvent.CANCEL,
                                String.format("批量关单 CAS 冲突，更新失败 [orderId=%d, source=%s, version=%d]",
                                        order.getId(), currentSource, order.getVersion())
                        );
                    }
                    order.setStatus(targetState);
                    order.setVersion(order.getVersion() + 1);
                },
                OrderEvent.CANCEL,
                context,
                options
        );

        log.info("[BatchCancel] 批量关单执行完成: 总数={}, 成功={}, 失败={}",
                result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());

        return result;
    }

    /**
     * 模拟切片批处理局部失败容错（部分订单符合条件成功，部分订单状态不匹配失败）
     *
     * @return 批量执行结果视图对象（包含成功列表与失败原因明细）
     */
    public BatchTransitionResult<OrderState, OrderDO> simulatePartialFailure() {
        log.info("[BatchCancel] 模拟批处理部分失败容错场景");

        if (lifecycleService == null) {
            log.warn("[BatchCancel] lifecycleService 未注入，跳过模拟");
            return BatchTransitionResult.empty();
        }

        // 1. 创建一笔合法 WAIT_PAY 订单
        String validSn = "ORD-BATCH-OK-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO validOrder = lifecycleService.createOrder(validSn, new BigDecimal("88.00"));

        // 2. 创建一笔不符合 CANCEL 条件的 DELIVERING 订单
        String invalidSn = "ORD-BATCH-FAIL-" + UUID.randomUUID().toString().substring(0, 8);
        OrderDO deliveredOrder = lifecycleService.createOrder(invalidSn, new BigDecimal("168.00"));
        lifecycleService.pay(deliveredOrder.getId());
        lifecycleService.auditRisk(deliveredOrder.getId(), 20, false); // 转为 WAIT_DELIVER
        lifecycleService.ship(deliveredOrder.getId(), "SF_BATCH_TEST"); // 转为 DELIVERING (不可直接 CANCEL)

        List<Long> mixIds = new ArrayList<>();
        mixIds.add(validOrder.getId());
        mixIds.add(deliveredOrder.getId());

        return batchCancelExpiredOrders(
                mixIds,
                "容错自验：部分合法取消，部分非法拦截",
                10
        );
    }
}
