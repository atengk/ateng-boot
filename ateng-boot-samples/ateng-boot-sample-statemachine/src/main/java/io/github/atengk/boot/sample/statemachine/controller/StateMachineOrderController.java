package io.github.atengk.boot.sample.statemachine.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.dto.OrderAddTrackingRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderRiskAuditRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderShipRequest;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.service.OrderLifecycleScenarioService;
import io.github.atengk.boot.sample.statemachine.vo.OrderTransitionVO;
import io.github.atengk.boot.sample.statemachine.vo.OrderVO;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单生命周期与状态流转业务控制器
 * <p>
 * 提供电商订单创建、详情查询、主干外部流转、内部自跃迁更新物理字段、Choice 动态风控条件分流、
 * 多源取消汇聚与多源退款申请全流程 HTTP 体验端点。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/statemachine/order")
@RequiredArgsConstructor
public class StateMachineOrderController {

    private final OrderLifecycleScenarioService lifecycleService;

    /**
     * 1. 创建待支付初始订单
     *
     * @param request 创建订单参数
     * @return 订单详情视图对象
     */
    @PostMapping("/create")
    public Result<OrderVO> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        log.info("[OrderController] 创建初始订单: orderSn={}, amount={}", request.getOrderSn(), request.getAmount());
        OrderDO order = lifecycleService.createOrder(request.getOrderSn(), request.getAmount());
        return Result.success(OrderVO.fromDO(order));
    }

    /**
     * 2. 查询订单当前详情与状态
     *
     * @param orderId 订单 ID
     * @return 订单详情视图对象
     */
    @GetMapping("/{orderId}")
    public Result<OrderVO> getOrder(@PathVariable Long orderId) {
        log.info("[OrderController] 查询订单详情: orderId={}", orderId);
        OrderDO order = lifecycleService.getOrder(orderId);
        return Result.success(OrderVO.fromDO(order));
    }

    /**
     * 3. 外部主干跃迁：买家付款 (WAIT_PAY -> PAID)
     *
     * @param orderId 订单 ID
     * @return 跃迁结果展示对象
     */
    @PostMapping("/{orderId}/pay")
    public Result<OrderTransitionVO> pay(@PathVariable Long orderId) {
        log.info("[OrderController] 触发买家付款跃迁: orderId={}", orderId);
        TransitionResult<OrderState> result = lifecycleService.pay(orderId);
        return Result.success(OrderTransitionVO.fromResult(orderId, result));
    }

    /**
     * 4. Choice 动态条件分支路由：风控评估分流 (PAID -> REFUNDING / WAIT_DELIVER)
     *
     * @param request 风控评分参数
     * @return 跃迁结果展示对象
     */
    @PostMapping("/risk-audit")
    public Result<OrderTransitionVO> riskAudit(@Valid @RequestBody OrderRiskAuditRequest request) {
        log.info("[OrderController] 触发风控评估 Choice 跃迁: orderId={}, score={}", request.getOrderId(), request.getRiskScore());
        TransitionResult<OrderState> result = lifecycleService.auditRisk(request.getOrderId(), request.getRiskScore(), false);
        return Result.success(OrderTransitionVO.fromResult(request.getOrderId(), result));
    }

    /**
     * 5. 外部主干跃迁：商家发货出库 (WAIT_DELIVER -> DELIVERING)
     *
     * @param request 发货参数
     * @return 跃迁结果展示对象
     */
    @PostMapping("/ship")
    public Result<OrderTransitionVO> ship(@Valid @RequestBody OrderShipRequest request) {
        log.info("[OrderController] 触发商家发货跃迁: orderId={}, trackingNo={}", request.getOrderId(), request.getTrackingNo());
        TransitionResult<OrderState> result = lifecycleService.ship(request.getOrderId(), request.getTrackingNo());
        return Result.success(OrderTransitionVO.fromResult(request.getOrderId(), result));
    }

    /**
     * 6. 内部自跃迁：追加物流轨迹 (DELIVERING -> DELIVERING，不离开主状态且不触发生命周期)
     *
     * @param request 追加物流参数
     * @return 跃迁结果展示对象
     */
    @PostMapping("/add-tracking")
    public Result<OrderTransitionVO> addTracking(@Valid @RequestBody OrderAddTrackingRequest request) {
        log.info("[OrderController] 触发内部自跃迁追加物流: orderId={}, remark={}", request.getOrderId(), request.getTrackingRemark());
        OrderDO order = lifecycleService.getOrder(request.getOrderId());
        String trackingNo = (request.getTrackingNo() != null && !request.getTrackingNo().isBlank())
                ? request.getTrackingNo()
                : (order.getTrackingNo() != null ? order.getTrackingNo() : "DEFAULT_TRACKING");
        TransitionResult<OrderState> result = lifecycleService.addTracking(request.getOrderId(), trackingNo, request.getTrackingRemark());
        return Result.success(OrderTransitionVO.fromResult(request.getOrderId(), result));
    }

    /**
     * 7. 外部主干跃迁：买家妥投签收 (DELIVERING -> DELIVERED)
     *
     * @param orderId 订单 ID
     * @return 跃迁结果展示对象
     */
    @PostMapping("/{orderId}/confirm-delivery")
    public Result<OrderTransitionVO> confirmDelivery(@PathVariable Long orderId) {
        log.info("[OrderController] 触发买家妥投签收: orderId={}", orderId);
        TransitionResult<OrderState> result = lifecycleService.confirmDelivery(orderId);
        return Result.success(OrderTransitionVO.fromResult(orderId, result));
    }

    /**
     * 8. 外部主干跃迁：交易完成归档 (DELIVERED -> FINISHED)
     *
     * @param orderId 订单 ID
     * @return 跃迁结果展示对象
     */
    @PostMapping("/{orderId}/finish")
    public Result<OrderTransitionVO> finish(@PathVariable Long orderId) {
        log.info("[OrderController] 触发交易完成归档: orderId={}", orderId);
        TransitionResult<OrderState> result = lifecycleService.finish(orderId);
        return Result.success(OrderTransitionVO.fromResult(orderId, result));
    }

    /**
     * 9. 多源汇聚跃迁：取消订单 ([WAIT_PAY, PAID, WAIT_DELIVER] -> CLOSED)
     *
     * @param orderId 订单 ID
     * @return 跃迁结果展示对象
     */
    @PostMapping("/{orderId}/cancel")
    public Result<OrderTransitionVO> cancel(@PathVariable Long orderId) {
        log.info("[OrderController] 触发多源汇聚取消订单: orderId={}", orderId);
        TransitionResult<OrderState> result = lifecycleService.cancel(orderId, "用户主动取消订单");
        return Result.success(OrderTransitionVO.fromResult(orderId, result));
    }

    /**
     * 10. 多源汇聚跃迁：申请退款 ([PAID, WAIT_DELIVER] -> REFUNDING)
     *
     * @param orderId 订单 ID
     * @return 跃迁结果展示对象
     */
    @PostMapping("/{orderId}/apply-refund")
    public Result<OrderTransitionVO> applyRefund(@PathVariable Long orderId) {
        log.info("[OrderController] 触发多源汇聚申请退款: orderId={}", orderId);
        TransitionResult<OrderState> result = lifecycleService.applyRefund(orderId, "买家申请退款");
        return Result.success(OrderTransitionVO.fromResult(orderId, result));
    }
}
