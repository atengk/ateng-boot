package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.redis.model.idempotent.PaymentCallbackRequest;
import io.github.atengk.boot.sample.redis.model.idempotent.PaymentCallbackVO;
import io.github.atengk.boot.sample.redis.model.idempotent.PaymentFailureRetryRequest;
import io.github.atengk.boot.sample.redis.model.idempotent.RefundRequest;
import io.github.atengk.boot.sample.redis.model.idempotent.RefundVO;
import io.github.atengk.boot.sample.redis.service.IdempotentScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分布式防重幂等控制器
 * 演示声明式注解（@RedisIdempotent）、失败自愈（deleteOnFailure）与编程式/静态门面防重核销
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/redis/idempotent")
@RequiredArgsConstructor
public class RedisIdempotentController {

    private final IdempotentScenarioService idempotentScenarioService;

    /**
     * 第三方支付异步回调通知防重入账
     *
     * @param request 支付回调入参
     * @return 支付回调处理结果 VO
     */
    @PostMapping("/payment/notify")
    public Result<PaymentCallbackVO> handlePaymentNotify(@Valid @RequestBody PaymentCallbackRequest request) {
        log.info("[RedisIdempotentController] 接收第三方支付异步通知: paymentNo={}, amount={}",
                request.getPaymentNo(), request.getAmount());
        PaymentCallbackVO vo = idempotentScenarioService.handlePaymentCallback(request);
        return Result.success(vo);
    }

    /**
     * 模拟瞬时异常触发自动释放防重凭据，支持二次重试自愈
     *
     * @param request 模拟异常重试请求
     * @return 支付回调处理结果 VO
     */
    @PostMapping("/payment/simulate-failure-retry")
    public Result<PaymentCallbackVO> simulateFailureRetry(@Valid @RequestBody PaymentFailureRetryRequest request) {
        log.info("[RedisIdempotentController] 模拟瞬时故障重试处理: paymentNo={}, shouldFail={}",
                request.getPaymentNo(), request.getShouldFail());
        PaymentCallbackVO vo = idempotentScenarioService.simulateFailureAndRetry(request);
        return Result.success(vo);
    }

    /**
     * 编程式模板防重核销退款单
     *
     * @param request 退款请求
     * @return 退款处理结果 VO
     */
    @PostMapping("/refund/template")
    public Result<RefundVO> executeRefundTemplate(@Valid @RequestBody RefundRequest request) {
        log.info("[RedisIdempotentController] 编程式模板退款核销: refundNo={}, amount={}",
                request.getRefundNo(), request.getRefundAmount());
        RefundVO vo = idempotentScenarioService.executeRefundTemplate(request);
        return Result.success(vo);
    }

    /**
     * 静态门面防重核销退款单
     *
     * @param request 退款请求
     * @return 退款处理结果 VO
     */
    @PostMapping("/refund/static")
    public Result<RefundVO> executeRefundStatic(@Valid @RequestBody RefundRequest request) {
        log.info("[RedisIdempotentController] 静态门面退款核销: refundNo={}, amount={}",
                request.getRefundNo(), request.getRefundAmount());
        RefundVO vo = idempotentScenarioService.executeRefundStatic(request);
        return Result.success(vo);
    }
}
