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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单预检探测场景服务
 * <p>演示零副作用只读探测门面 (canFire / verifyTransition / getAvailableEvents)，支撑前端动态按钮权限与流程推演</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
public class OrderProbeScenarioService {

    private static final Logger log = LoggerFactory.getLogger(OrderProbeScenarioService.class);

    private final OrderMapper orderMapper;
    private final StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;

    public OrderProbeScenarioService(OrderMapper orderMapper,
                                     StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine) {
        this.orderMapper = orderMapper;
        this.orderStateMachine = orderStateMachine;
    }

    /**
     * 探测指定事件是否可流转（零副作用，绝不触发 Action / Guard 异常抛出 / 数据库写操作）
     *
     * @param orderId 订单 ID
     * @param event   触发事件
     * @param context 业务上下文（可传 null）
     * @return true 若允许流转
     */
    public boolean canFire(Long orderId, OrderEvent event, OrderContext context) {
        OrderDO order = getOrderOrThrow(orderId);
        boolean allowed = orderStateMachine.canFire(order.getStatus(), event, context);
        log.info("[Probe] 探测流转可行性: orderSn={}, status={}, event={}, allowed={}",
                order.getOrderSn(), order.getStatus(), event, allowed);
        return allowed;
    }

    /**
     * 探测当前订单是否可执行支付操作
     *
     * @param orderId 订单 ID
     * @return true 若处于待支付且金额合法
     */
    public boolean canPay(Long orderId) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .amount(order.getAmount())
                .build();
        return orderStateMachine.canFire(order.getStatus(), OrderEvent.PAY, context);
    }

    /**
     * 探测当前订单是否可执行发货操作
     *
     * @param orderId    订单 ID
     * @param trackingNo 运单号
     * @return true 若状态与单号符合发货前置 Guard
     */
    public boolean canShip(Long orderId, String trackingNo) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .trackingNo(trackingNo)
                .build();
        return orderStateMachine.canFire(order.getStatus(), OrderEvent.SHIP, context);
    }

    /**
     * 探测当前订单是否可执行取消操作
     *
     * @param orderId 订单 ID
     * @return true 若处于 WAIT_PAY / PAID / WAIT_DELIVER
     */
    public boolean canCancel(Long orderId) {
        OrderDO order = getOrderOrThrow(orderId);
        return orderStateMachine.canFire(order.getStatus(), OrderEvent.CANCEL, null);
    }

    /**
     * 预检推演目标状态（结构化预检结果，不触发 Action）
     *
     * @param orderId 订单 ID
     * @param event   触发事件
     * @param context 业务上下文
     * @return 结构化预检结果
     */
    public TransitionResult<OrderState> previewNextState(Long orderId, OrderEvent event, OrderContext context) {
        OrderDO order = getOrderOrThrow(orderId);
        return orderStateMachine.verifyTransition(order.getStatus(), event, context);
    }

    /**
     * 校验并推演状态跃迁可行性与目标状态（verifyTransition 显式契约门面）
     *
     * @param orderId 订单 ID
     * @param event   触发事件
     * @param context 业务上下文
     * @return 结构化预检结果
     */
    public TransitionResult<OrderState> verifyTransition(Long orderId, OrderEvent event, OrderContext context) {
        return previewNextState(orderId, event, context);
    }

    /**
     * 校验并推演状态跃迁可行性与目标状态（免上下文重载）
     *
     * @param orderId 订单 ID
     * @param event   触发事件
     * @return 结构化预检结果
     */
    public TransitionResult<OrderState> verifyTransition(Long orderId, OrderEvent event) {
        return verifyTransition(orderId, event, null);
    }

    /**
     * 获取当前订单状态下所有允许触发的事件列表
     *
     * @param orderId 订单 ID
     * @param context 业务上下文
     * @return 可用事件集合
     */
    public List<OrderEvent> getAvailableEvents(Long orderId, OrderContext context) {
        OrderDO order = getOrderOrThrow(orderId);
        Set<OrderEvent> events = orderStateMachine.getAvailableEvents(order.getStatus(), context);
        return new ArrayList<>(events);
    }

    /**
     * 为前端控制台综合评估动态按钮权限视图
     *
     * @param orderId 订单 ID
     * @return 按钮名称与布尔权限映射表
     */
    public Map<String, Boolean> evaluateButtonPermissions(Long orderId) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderContext payCtx = OrderContext.builder().orderId(order.getId()).amount(order.getAmount()).build();
        OrderContext shipCtx = OrderContext.builder().orderId(order.getId()).trackingNo("PROBE_TEST").build();

        Map<String, Boolean> permissions = new LinkedHashMap<>();
        permissions.put("btnPay", orderStateMachine.canFire(order.getStatus(), OrderEvent.PAY, payCtx));
        permissions.put("btnShip", orderStateMachine.canFire(order.getStatus(), OrderEvent.SHIP, shipCtx));
        permissions.put("btnCancel", orderStateMachine.canFire(order.getStatus(), OrderEvent.CANCEL, null));
        permissions.put("btnRefund", orderStateMachine.canFire(order.getStatus(), OrderEvent.APPLY_REFUND, null));
        permissions.put("btnConfirmDelivery", orderStateMachine.canFire(order.getStatus(), OrderEvent.CONFIRM_DELIVERY, null));
        return permissions;
    }

    private OrderDO getOrderOrThrow(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "订单不存在: id=" + orderId);
        }
        return order;
    }
}
