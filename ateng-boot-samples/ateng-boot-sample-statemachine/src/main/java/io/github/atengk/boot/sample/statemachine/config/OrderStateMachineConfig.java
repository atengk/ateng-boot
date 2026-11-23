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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 电商订单 8 大状态全生命周期状态机编排配置类
 * <p>涵盖主干流转、内部自跃迁、多源汇聚、Choice 分支、节点 Entry/Exit 生命周期、声明式幂等与事务提交后动作</p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Configuration
public class OrderStateMachineConfig {

    private static final Logger log = LoggerFactory.getLogger(OrderStateMachineConfig.class);

    public static final String MACHINE_ID = "ORDER_LIFECYCLE";

    /**
     * 记录节点生命周期执行轨迹（便于测试断言与自验）
     */
    private final List<String> lifecycleRecords = new CopyOnWriteArrayList<>();

    /**
     * 记录事务提交后通知执行轨迹
     */
    private final List<String> postCommitRecords = new CopyOnWriteArrayList<>();

    public List<String> getLifecycleRecords() {
        return lifecycleRecords;
    }

    public List<String> getPostCommitRecords() {
        return postCommitRecords;
    }

    public void clearRecords() {
        lifecycleRecords.clear();
        postCommitRecords.clear();
    }

    @Bean
    public StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine() {
        StateMachineBuilder<OrderState, OrderEvent, OrderContext> builder = StateMachineBuilder.builder();

        // ==================== 状态节点 Entry / Exit 生命周期动作 ====================
        builder.state(OrderState.WAIT_DELIVER)
                .onEntry((from, to, event, ctx) -> {
                    String record = "ENTRY:" + to + ":" + (ctx != null ? ctx.getOrderSn() : "UNKNOWN");
                    lifecycleRecords.add(record);
                    log.info("[State-Entry] 进入待发货状态: from={}, to={}, event={}, orderSn={}",
                            from, to, event, ctx != null ? ctx.getOrderSn() : "UNKNOWN");
                })
                .onExit((from, to, event, ctx) -> {
                    String record = "EXIT:" + from + ":" + (ctx != null ? ctx.getOrderSn() : "UNKNOWN");
                    lifecycleRecords.add(record);
                    log.info("[State-Exit] 离开待发货状态: from={}, to={}, event={}, orderSn={}",
                            from, to, event, ctx != null ? ctx.getOrderSn() : "UNKNOWN");
                });

        builder.state(OrderState.CLOSED)
                .onEntry((from, to, event, ctx) -> {
                    String record = "ENTRY:" + to + ":" + (ctx != null ? ctx.getOrderSn() : "UNKNOWN");
                    lifecycleRecords.add(record);
                    log.info("[State-Entry] 订单关闭终态，触发库存回退: orderSn={}",
                            ctx != null ? ctx.getOrderSn() : "UNKNOWN");
                });

        // ==================== 状态跃迁流转定义 ====================

        // 1. 外部主干跃迁：WAIT_PAY -> PAID (PAY)，配置声明式幂等放行策略与事务提交后不可逆通知
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .idempotent(true)
                .when(ctx -> ctx != null && ctx.getAmount() != null)
                .perform((from, to, event, ctx) ->
                        log.info("[Action] 订单支付成功: orderSn={}, amount={}", ctx.getOrderSn(), ctx.getAmount()))
                .performAfterCommit((from, to, event, ctx) -> {
                    String record = "POST_COMMIT_NOTIFY:" + (ctx != null ? ctx.getOrderSn() : "UNKNOWN");
                    postCommitRecords.add(record);
                    log.info("[PostCommit] 订单支付本地事务已提交，触发外部不可逆短信/履约通知: orderSn={}",
                            ctx != null ? ctx.getOrderSn() : "UNKNOWN");
                });

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
                .perform((from, to, event, ctx) ->
                        log.info("[Action] 订单发货出库: orderSn={}, trackingNo={}", ctx.getOrderSn(), ctx.getTrackingNo()));

        // 4. 内部自跃迁：DELIVERING -> DELIVERING (ADD_TRACKING) 保持状态不变
        builder.internalTransition()
                .from(OrderState.DELIVERING)
                .on(OrderEvent.ADD_TRACKING)
                .when(ctx -> ctx != null && ctx.getTrackingRemark() != null && !ctx.getTrackingRemark().isBlank())
                .perform((from, to, event, ctx) ->
                        log.info("[Action-Internal] 追加物流动态: orderSn={}, status={}, remark={}",
                                ctx.getOrderSn(), from, ctx.getTrackingRemark()));

        // 5. 外部主干跃迁：DELIVERING -> DELIVERED (CONFIRM_DELIVERY)
        builder.externalTransition()
                .from(OrderState.DELIVERING)
                .to(OrderState.DELIVERED)
                .on(OrderEvent.CONFIRM_DELIVERY)
                .perform((from, to, event, ctx) ->
                        log.info("[Action] 买家签收妥投: orderSn={}", ctx.getOrderSn()));

        // 6. 外部主干跃迁：DELIVERED -> FINISHED (FINISH)
        builder.externalTransition()
                .from(OrderState.DELIVERED)
                .to(OrderState.FINISHED)
                .on(OrderEvent.FINISH)
                .perform((from, to, event, ctx) ->
                        log.info("[Action] 订单交易完成归档: orderSn={}", ctx.getOrderSn()));

        // 7. 多源汇聚跃迁：[WAIT_PAY, PAID, WAIT_DELIVER] 统一汇聚流向 CLOSED (CANCEL)
        builder.multiFromTransition()
                .from(OrderState.WAIT_PAY, OrderState.PAID, OrderState.WAIT_DELIVER)
                .to(OrderState.CLOSED)
                .on(OrderEvent.CANCEL)
                .perform((from, to, event, ctx) ->
                        log.info("[Action-MultiFrom] 订单取消并关闭: orderSn={}, 原状态={}",
                                ctx != null ? ctx.getOrderSn() : "UNKNOWN", from));

        // 8. 逆向售后多源跃迁：[PAID, WAIT_DELIVER] -> REFUNDING (APPLY_REFUND)
        builder.multiFromTransition()
                .from(OrderState.PAID, OrderState.WAIT_DELIVER)
                .to(OrderState.REFUNDING)
                .on(OrderEvent.APPLY_REFUND)
                .perform((from, to, event, ctx) ->
                        log.info("[Action-MultiFrom] 发起售后退款: orderSn={}, 原状态={}",
                                ctx != null ? ctx.getOrderSn() : "UNKNOWN", from));

        // 9. 外部跃迁：REFUNDING -> CLOSED (FINISH)
        builder.externalTransition()
                .from(OrderState.REFUNDING)
                .to(OrderState.CLOSED)
                .on(OrderEvent.FINISH)
                .perform((from, to, event, ctx) ->
                        log.info("[Action] 退款完成，订单终态关闭: orderSn={}", ctx.getOrderSn()));

        return builder.build(MACHINE_ID);
    }
}
