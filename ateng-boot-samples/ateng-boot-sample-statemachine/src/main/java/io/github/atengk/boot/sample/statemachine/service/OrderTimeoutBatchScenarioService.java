package io.github.atengk.boot.sample.statemachine.service;

import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.mapper.OrderMapper;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.model.BatchOptions;
import io.github.atengk.boot.statemachine.model.BatchTransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public OrderTimeoutBatchScenarioService(OrderMapper orderMapper,
                                            StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine) {
        this.orderMapper = orderMapper;
        this.orderStateMachine = orderStateMachine;
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
                OrderDO::setStatus,
                OrderEvent.CANCEL,
                context,
                options
        );

        // 持久化成功批量流转的订单状态
        for (OrderDO order : result.getSuccessEntities()) {
            orderMapper.updateStatusWithCas(order.getId(), OrderState.WAIT_PAY.name(), OrderState.CLOSED.name(), order.getVersion());
            order.setVersion(order.getVersion() + 1);
        }

        log.info("[BatchCancel] 批量关单执行完成: 总数={}, 成功={}, 失败={}",
                result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());

        return result;
    }
}
