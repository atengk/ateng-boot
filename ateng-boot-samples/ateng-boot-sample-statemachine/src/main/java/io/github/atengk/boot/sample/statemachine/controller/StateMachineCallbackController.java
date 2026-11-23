package io.github.atengk.boot.sample.statemachine.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.dto.OrderPaymentCallbackRequest;
import io.github.atengk.boot.sample.statemachine.service.PaymentCallbackScenarioService;
import io.github.atengk.boot.sample.statemachine.vo.OrderConcurrentSimulationVO;
import io.github.atengk.boot.sample.statemachine.vo.OrderTransitionVO;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付异步回调与并发幂等控制器
 * <p>
 * 演示第三方支付异步回调处理、CAS-First 乐观锁前置并发防重冲突阻断、
 * 声明式幂等策略放行 (idempotent(true)) 与受管并发线程池高并发调度。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/statemachine/callback")
@RequiredArgsConstructor
public class StateMachineCallbackController {

    private final PaymentCallbackScenarioService callbackService;

    /**
     * 1. 处理第三方支付异步回调（带 CAS 乐观锁防重与声明式幂等放行）
     *
     * @param request 支付回调入参
     * @return 跃迁处理结果
     */
    @PostMapping("/payment")
    public Result<OrderTransitionVO> handlePaymentCallback(@Valid @RequestBody OrderPaymentCallbackRequest request) {
        log.info("[CallbackController] 收到支付异步回调: orderId={}, amount={}, channelTxNo={}",
                request.getOrderId(), request.getPaidAmount(), request.getChannelTxNo());
        TransitionResult<OrderState> result = callbackService.handlePaymentCallback(
                request.getOrderId(),
                request.getPaidAmount(),
                request.getChannelTxNo()
        );
        return Result.success(OrderTransitionVO.fromResult(request.getOrderId(), result));
    }

    /**
     * 2. 模拟多线程高并发重复支付回调冲击（验证 CAS-First 原子冲突阻断机制与受管并发调度）
     *
     * @param orderId 待测试订单 ID
     * @param threads 并发线程数（默认 5）
     * @return 强类型并发模拟执行统计视图对象
     */
    @PostMapping("/simulate-concurrent")
    public Result<OrderConcurrentSimulationVO> simulateConcurrentCallbacks(
            @RequestParam Long orderId,
            @RequestParam(defaultValue = "5") int threads) {
        log.info("[CallbackController] 触发高并发回调冲击模拟: orderId={}, 并发数={}", orderId, threads);
        OrderConcurrentSimulationVO vo = callbackService.simulateConcurrentCallbacks(orderId, threads);
        return Result.success(vo);
    }
}
