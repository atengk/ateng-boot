package io.github.atengk.boot.sample.statemachine.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.dto.OrderPaymentCallbackRequest;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.service.OrderLifecycleScenarioService;
import io.github.atengk.boot.sample.statemachine.service.PaymentCallbackScenarioService;
import io.github.atengk.boot.sample.statemachine.vo.OrderTransitionVO;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 支付异步回调与并发幂等控制器
 * <p>
 * 演示第三方支付异步回调处理、CAS-First 乐观锁前置并发防重冲突阻断、
 * 声明式幂等策略放行 (idempotent(true)) 与本地事务提交后 (performAfterCommit) 外部不可逆通知。
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
    private final OrderLifecycleScenarioService lifecycleService;

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
     * 2. 模拟多线程高并发重复支付回调冲击（验证 CAS-First 原子冲突阻断机制）
     *
     * @param orderId 待测试订单 ID
     * @param threads 并发线程数（默认 5）
     * @return 并发执行统计结果
     */
    @PostMapping("/simulate-concurrent")
    public Result<Map<String, Object>> simulateConcurrentCallbacks(
            @RequestParam Long orderId,
            @RequestParam(defaultValue = "5") int threads) {
        int concurrency = Math.max(2, Math.min(threads, 20));
        log.info("[CallbackController] 模拟高并发回调冲击: orderId={}, 并发数={}", orderId, concurrency);

        OrderDO initialOrder = lifecycleService.getOrder(orderId);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger idempotentCount = new AtomicInteger(0);
        AtomicInteger casConflictCount = new AtomicInteger(0);
        AtomicInteger otherFailures = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            for (int i = 0; i < concurrency; i++) {
                final int idx = i;
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await(5, TimeUnit.SECONDS);
                        TransitionResult<OrderState> res = callbackService.handlePaymentCallback(
                                orderId,
                                initialOrder.getAmount(),
                                "SIM_TX_" + idx + "_" + UUID.randomUUID().toString().substring(0, 8)
                        );
                        if (res != null && res.isAccepted()) {
                            if (res.isIdempotent()) {
                                idempotentCount.incrementAndGet();
                            } else {
                                successCount.incrementAndGet();
                            }
                        }
                    } catch (StateMachineException ex) {
                        if (ex.getResultCode() == StateMachineResultCode.CONCURRENT_CAS_CONFLICT) {
                            casConflictCount.incrementAndGet();
                        } else {
                            otherFailures.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        otherFailures.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

        OrderDO finalOrder = lifecycleService.getOrder(orderId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", orderId);
        summary.put("concurrency", concurrency);
        summary.put("firstSuccessCount", successCount.get());
        summary.put("idempotentAcceptedCount", idempotentCount.get());
        summary.put("casConflictBlockedCount", casConflictCount.get());
        summary.put("otherFailures", otherFailures.get());
        summary.put("finalStatus", finalOrder.getStatus());
        summary.put("finalVersion", finalOrder.getVersion());
        summary.put("verdict", (successCount.get() == 1 || idempotentCount.get() > 0)
                ? "CAS并发防护生效：严格防止重复支付与虚假副作用"
                : "并发流转完成");

        return Result.success(summary);
    }
}
