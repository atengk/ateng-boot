package io.github.atengk.boot.sample.statemachine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.time.LocalDateTime;

/**
 * 订单生命周期场景服务
 * <p>演示外部主干流转、内部自跃迁、多源汇聚、Choice 条件动态分支与节点 Entry/Exit 生命周期</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
public class OrderLifecycleScenarioService {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleScenarioService.class);

    private final OrderMapper orderMapper;
    private final StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;

    public OrderLifecycleScenarioService(OrderMapper orderMapper,
                                         StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine) {
        this.orderMapper = orderMapper;
        this.orderStateMachine = orderStateMachine;
    }

    /**
     * 创建待支付初始订单
     *
     * @param orderSn 订单编号
     * @param amount  订单金额
     * @return 持久化订单实体
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDO createOrder(String orderSn, BigDecimal amount) {
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getOrderSn, orderSn));
        if (count != null && count > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "订单编号已存在: " + orderSn);
        }

        OrderDO order = OrderDO.builder()
                .orderSn(orderSn)
                .status(OrderState.WAIT_PAY)
                .amount(amount != null ? amount : BigDecimal.ZERO)
                .version(0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        orderMapper.insert(order);
        log.info("成功创建初始订单: id={}, orderSn={}, status={}", order.getId(), orderSn, order.getStatus());
        return order;
    }

    /**
     * 查询订单当前详情
     *
     * @param orderId 订单主键
     * @return 订单持久化对象
     */
    public OrderDO getOrder(Long orderId) {
        return getOrderOrThrow(orderId);
    }

    /**
     * 外部主干跃迁：买家付款 (WAIT_PAY -> PAID)
     *
     * @param orderId 订单 ID
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> pay(Long orderId) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .amount(order.getAmount())
                .operator("BUYER")
                .build();

        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.PAY,
                context,
                (ent, from, to) -> orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion())
        );

        order.setVersion(order.getVersion() + 1);
        return TransitionResult.success(sourceState, targetState, OrderEvent.PAY);
    }

    /**
     * Choice 动态条件分支路由：风控评估 (PAID -> REFUNDING(高风险拦截) / WAIT_DELIVER(正常放行))
     *
     * @param orderId     订单 ID
     * @param riskScore   风控评分 (>= 80 判定高风险拦截)
     * @param failTrigger 是否模拟 Action 业务异常以触发本地事务回滚
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> auditRisk(Long orderId, int riskScore, boolean failTrigger) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .amount(order.getAmount())
                .riskScore(riskScore)
                .failTrigger(failTrigger)
                .operator("RISK_SYSTEM")
                .build();

        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.RISK_AUDIT,
                context,
                (ent, from, to) -> orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion())
        );

        order.setVersion(order.getVersion() + 1);
        order.setRiskScore(riskScore);
        orderMapper.updateById(order);

        return TransitionResult.success(sourceState, targetState, OrderEvent.RISK_AUDIT);
    }

    /**
     * 外部主干跃迁：商家发货出库 (WAIT_DELIVER -> DELIVERING)
     *
     * @param orderId    订单 ID
     * @param trackingNo 运单号
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> ship(Long orderId, String trackingNo) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .trackingNo(trackingNo)
                .operator("MERCHANT")
                .build();

        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.SHIP,
                context,
                (ent, from, to) -> orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion())
        );

        order.setVersion(order.getVersion() + 1);
        order.setTrackingNo(trackingNo);
        orderMapper.updateById(order);

        return TransitionResult.success(sourceState, targetState, OrderEvent.SHIP);
    }

    /**
     * 内部自跃迁：追加物流追踪动态 (DELIVERING 状态保持不变)
     *
     * @param orderId    订单 ID
     * @param trackingNo 运单号
     * @param remark     物流动态说明
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> addTracking(Long orderId, String trackingNo, String remark) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .trackingNo(trackingNo)
                .trackingRemark(remark)
                .operator("LOGISTICS_API")
                .build();

        OrderState targetState = orderStateMachine.fireEvent(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.ADD_TRACKING,
                context
        );

        orderMapper.updateTracking(orderId, trackingNo, remark);
        return TransitionResult.success(sourceState, targetState, OrderEvent.ADD_TRACKING);
    }

    /**
     * 外部主干跃迁：买家签收妥投 (DELIVERING -> DELIVERED)
     *
     * @param orderId 订单 ID
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> confirmDelivery(Long orderId) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .operator("BUYER")
                .build();

        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.CONFIRM_DELIVERY,
                context,
                (ent, from, to) -> orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion())
        );

        order.setVersion(order.getVersion() + 1);
        return TransitionResult.success(sourceState, targetState, OrderEvent.CONFIRM_DELIVERY);
    }

    /**
     * 外部主干跃迁：交易完成归档 (DELIVERED -> FINISHED)
     *
     * @param orderId 订单 ID
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> finish(Long orderId) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .operator("SYSTEM")
                .build();

        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.FINISH,
                context,
                (ent, from, to) -> orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion())
        );

        order.setVersion(order.getVersion() + 1);
        return TransitionResult.success(sourceState, targetState, OrderEvent.FINISH);
    }

    /**
     * 多源汇聚跃迁：取消订单 ([WAIT_PAY, PAID, WAIT_DELIVER] -> CLOSED)
     *
     * @param orderId 订单 ID
     * @param reason  取消原因
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> cancel(Long orderId, String reason) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .trackingRemark(reason)
                .operator("USER")
                .build();

        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.CANCEL,
                context,
                (ent, from, to) -> orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion())
        );

        order.setVersion(order.getVersion() + 1);
        return TransitionResult.success(sourceState, targetState, OrderEvent.CANCEL);
    }

    /**
     * 多源逆向售后跃迁：发起退款 ([PAID, WAIT_DELIVER] -> REFUNDING)
     *
     * @param orderId 订单 ID
     * @param reason  退款原因
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> applyRefund(Long orderId, String reason) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .trackingRemark(reason)
                .operator("BUYER")
                .build();

        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.APPLY_REFUND,
                context,
                (ent, from, to) -> orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion())
        );

        order.setVersion(order.getVersion() + 1);
        return TransitionResult.success(sourceState, targetState, OrderEvent.APPLY_REFUND);
    }

    /**
     * 导出机台 Mermaid 状态图
     *
     * @return Mermaid 图例语法文本
     */
    public String exportMermaidDiagram() {
        return orderStateMachine.exportMermaidDiagram();
    }

    /**
     * 导出机台 PlantUML 状态图
     *
     * @return PlantUML 图例语法文本
     */
    public String exportPlantUmlDiagram() {
        return orderStateMachine.generatePlantUML();
    }

    private OrderDO getOrderOrThrow(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "订单不存在: id=" + orderId);
        }
        return order;
    }
}
