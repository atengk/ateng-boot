package io.github.atengk.boot.sample.statemachine.config;

import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.core.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 电商订单 8 大状态全生命周期状态机编排配置类
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Configuration
public class OrderStateMachineConfig {

    private static final Logger log = LoggerFactory.getLogger(OrderStateMachineConfig.class);

    public static final String MACHINE_ID = "ORDER_LIFECYCLE";

    @Bean
    public StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine() {
        StateMachineBuilder<OrderState, OrderEvent, OrderContext> builder = StateMachineBuilder.builder();

        // 1. 外部主干跃迁：WAIT_PAY -> PAID (PAY)
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .when(ctx -> ctx != null && ctx.getAmount() != null)
                .perform((from, to, event, ctx) -> log.info("[Action] 订单支付成功: orderSn={}, amount={}", ctx.getOrderSn(), ctx.getAmount()));

        // 2. Choice 动态条件分支路由：PAID -> (风控判断) -> REFUNDING(高风险拦截) / WAIT_DELIVER(正常放行)
        builder.choiceTransition()
                .from(OrderState.PAID)
                .on(OrderEvent.RISK_AUDIT)
                .when(ctx -> ctx != null && ctx.getRiskScore() != null && ctx.getRiskScore() >= 80)
                .to(OrderState.REFUNDING)
                .defaultTo(OrderState.WAIT_DELIVER)
                .perform((from, to, event, ctx) -> {
                    if (ctx != null && ctx.isFailTrigger()) {
                        log.warn("[Action] 触发模拟异常分支，抛出运行时异常以自验事务回滚: orderSn={}", ctx.getOrderSn());
                        throw new IllegalStateException("模拟 Action 执行业务异常，触发周围本地事务原子回滚！");
                    }
                    log.info("[Action] 订单风控评估完成: orderSn={}, score={}, 流转结果={} -> {}",
                            ctx != null ? ctx.getOrderSn() : "UNKNOWN",
                            ctx != null ? ctx.getRiskScore() : 0, from, to);
                });

        // 3. 外部主干跃迁：WAIT_DELIVER -> DELIVERING (SHIP)
        builder.externalTransition()
                .from(OrderState.WAIT_DELIVER)
                .to(OrderState.DELIVERING)
                .on(OrderEvent.SHIP)
                .when(ctx -> ctx != null && ctx.getTrackingNo() != null && !ctx.getTrackingNo().isBlank())
                .perform((from, to, event, ctx) -> log.info("[Action] 订单发货出库: orderSn={}, trackingNo={}", ctx.getOrderSn(), ctx.getTrackingNo()));

        // 4. 内部自跃迁：DELIVERING -> DELIVERING (ADD_TRACKING) 保持状态不变
        builder.internalTransition()
                .from(OrderState.DELIVERING)
                .on(OrderEvent.ADD_TRACKING)
                .when(ctx -> ctx != null && ctx.getTrackingRemark() != null && !ctx.getTrackingRemark().isBlank())
                .perform((from, to, event, ctx) -> log.info("[Action-Internal] 追加物流动态: orderSn={}, status={}, remark={}",
                        ctx.getOrderSn(), from, ctx.getTrackingRemark()));

        // 5. 外部主干跃迁：DELIVERING -> DELIVERED (CONFIRM_DELIVERY)
        builder.externalTransition()
                .from(OrderState.DELIVERING)
                .to(OrderState.DELIVERED)
                .on(OrderEvent.CONFIRM_DELIVERY)
                .perform((from, to, event, ctx) -> log.info("[Action] 买家签收妥投: orderSn={}", ctx.getOrderSn()));

        // 6. 外部主干跃迁：DELIVERED -> FINISHED (FINISH)
        builder.externalTransition()
                .from(OrderState.DELIVERED)
                .to(OrderState.FINISHED)
                .on(OrderEvent.FINISH)
                .perform((from, to, event, ctx) -> log.info("[Action] 订单交易完成归档: orderSn={}", ctx.getOrderSn()));

        // 7. 多源汇聚跃迁：[WAIT_PAY, PAID, WAIT_DELIVER] 统一汇聚流向 CLOSED (CANCEL)
        builder.multiFromTransition()
                .from(OrderState.WAIT_PAY, OrderState.PAID, OrderState.WAIT_DELIVER)
                .to(OrderState.CLOSED)
                .on(OrderEvent.CANCEL)
                .perform((from, to, event, ctx) -> log.info("[Action-MultiFrom] 订单取消并关闭: orderSn={}, 原状态={}",
                        ctx != null ? ctx.getOrderSn() : "UNKNOWN", from));

        // 8. 逆向售后多源跃迁：[PAID, WAIT_DELIVER] -> REFUNDING (APPLY_REFUND)
        builder.multiFromTransition()
                .from(OrderState.PAID, OrderState.WAIT_DELIVER)
                .to(OrderState.REFUNDING)
                .on(OrderEvent.APPLY_REFUND)
                .perform((from, to, event, ctx) -> log.info("[Action-MultiFrom] 发起售后退款: orderSn={}, 原状态={}",
                        ctx != null ? ctx.getOrderSn() : "UNKNOWN", from));

        // 9. 外部跃迁：REFUNDING -> CLOSED (FINISH)
        builder.externalTransition()
                .from(OrderState.REFUNDING)
                .to(OrderState.CLOSED)
                .on(OrderEvent.FINISH)
                .perform((from, to, event, ctx) -> log.info("[Action] 退款完成，订单终态关闭: orderSn={}", ctx.getOrderSn()));

        return builder.build(MACHINE_ID);
    }
}
