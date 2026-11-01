package io.github.atengk.boot.sample.statemachine.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.mapper.OrderMapper;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 第三方支付异步回调场景服务
 * <p>演示 CAS 乐观锁防重执行、声明式幂等放行 (IdempotentStrategy.ACCEPT) 与 Spring 本地事务提交后 (performAfterCommit) 外部通知</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
public class PaymentCallbackScenarioService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackScenarioService.class);

    private final OrderMapper orderMapper;
    private final StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;

    public PaymentCallbackScenarioService(OrderMapper orderMapper,
                                          StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine) {
        this.orderMapper = orderMapper;
        this.orderStateMachine = orderStateMachine;
    }

    /**
     * 处理外部渠道支付异步回调通知
     * <p>结合数据库原子 CAS 乐观锁防并发重复执行，若重复回调已处于 PAID 目标态则幂等放行，并在本地事务提交后同步触发外部通知</p>
     *
     * @param orderId     订单 ID
     * @param paidAmount  支付实付金额
     * @param channelTxNo 渠道交易流水号
     * @return 跃迁处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> handlePaymentCallback(Long orderId, BigDecimal paidAmount, String channelTxNo) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .amount(paidAmount != null ? paidAmount : order.getAmount())
                .operator("PAY_CALLBACK:" + (channelTxNo != null ? channelTxNo : "UNKNOWN"))
                .build();

        log.info("[PaymentCallback] 收到支付异步回调: orderId={}, orderSn={}, currentStatus={}, paidAmount={}",
                orderId, order.getOrderSn(), sourceState, paidAmount);

        // CAS 驱动跃迁（内置声明式幂等探测与 performAfterCommit 调度）
        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.PAY,
                context,
                (ent, from, to) -> orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion())
        );

        if (sourceState != targetState) {
            order.setVersion(order.getVersion() + 1);
            log.info("[PaymentCallback] 支付状态更新成功: orderId={}, status={} -> {}, version={}",
                    orderId, sourceState, targetState, order.getVersion());
        } else {
            log.info("[PaymentCallback] 支付回调幂等放行: orderId={}, 维持状态={}", orderId, targetState);
        }

        return TransitionResult.success(sourceState, targetState, OrderEvent.PAY);
    }

    private OrderDO getOrderOrThrow(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "订单不存在: id=" + orderId);
        }
        return order;
    }
}
