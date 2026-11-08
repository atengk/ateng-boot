package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.idempotent.IdempotentSupplier;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentTemplate;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentUtil;
import io.github.atengk.boot.redis.idempotent.annotation.RedisIdempotent;
import io.github.atengk.boot.sample.redis.model.idempotent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 非 Web 业务分布式防重幂等场景服务
 * 演示声明式注解拦截并发防重、业务失败 deleteOnFailure 释放凭据与编程式/静态防重模板
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentScenarioService {

    private final RedisIdempotentTemplate redisIdempotentTemplate;

    private final AtomicInteger successProcessCount = new AtomicInteger(0);
    private final AtomicInteger failureProcessCount = new AtomicInteger(0);

    /**
     * 第三方支付异步通知回调并发防重（声明式 @RedisIdempotent）
     *
     * @param request 支付通知请求
     * @return 业务处理结果 VO
     */
    @RedisIdempotent(key = "'idemp:pay:' + #request.paymentNo", expire = "60s", deleteOnFailure = true, message = "支付回调正在处理中或已处理完成，请勿重复通知")
    public PaymentCallbackVO handlePaymentCallback(PaymentCallbackRequest request) {
        log.info("[IdempotentScenario] 收到支付异步回调通知: paymentNo={}, orderId={}, amount={}",
                request.getPaymentNo(), request.getOrderId(), request.getAmount());
        successProcessCount.incrementAndGet();

        return PaymentCallbackVO.builder()
                .paymentNo(request.getPaymentNo())
                .orderId(request.getOrderId())
                .processed(true)
                .message("支付回调处理成功，账务已入账")
                .build();
    }

    /**
     * 模拟账务瞬时异常验证 deleteOnFailure = true 自动释放凭据并二次重试入账
     *
     * @param request 请求参数
     * @return 支付结果 VO
     */
    @RedisIdempotent(key = "'idemp:retry:' + #request.paymentNo", expire = "60s", deleteOnFailure = true, message = "支付流水处理中，请勿重复提交")
    public PaymentCallbackVO simulateFailureAndRetry(PaymentFailureRetryRequest request) {
        log.info("[IdempotentScenario] 处理瞬时故障模拟流水: paymentNo={}, shouldFail={}",
                request.getPaymentNo(), request.getShouldFail());

        if (Boolean.TRUE.equals(request.getShouldFail())) {
            failureProcessCount.incrementAndGet();
            throw new BusinessException("模拟业务处理失败，自动回滚释放幂等凭据以供重试: paymentNo=" + request.getPaymentNo());
        }

        successProcessCount.incrementAndGet();
        return PaymentCallbackVO.builder()
                .paymentNo(request.getPaymentNo())
                .orderId("ORD_" + request.getPaymentNo())
                .processed(true)
                .message("重试入账成功")
                .build();
    }

    /**
     * 编程式防重核销退款单（RedisIdempotentTemplate）
     *
     * @param request 退款请求
     * @return 退款结果 VO
     */
    public RefundVO executeRefundTemplate(RefundRequest request) {
        String key = "idemp:refund:tpl:" + request.getRefundNo();
        return redisIdempotentTemplate.execute(key, Duration.ofMinutes(1), () -> {
            log.info("[IdempotentScenario] 编程式模板防重处理退款: refundNo={}, amount={}",
                    request.getRefundNo(), request.getRefundAmount());
            successProcessCount.incrementAndGet();

            return RefundVO.builder()
                    .refundNo(request.getRefundNo())
                    .refundAmount(request.getRefundAmount())
                    .success(true)
                    .message("编程式模板退款核销成功")
                    .build();
        });
    }

    /**
     * 静态门面防重核销退款单（RedisIdempotentUtil）
     *
     * @param request 退款请求
     * @return 退款结果 VO
     */
    public RefundVO executeRefundStatic(RefundRequest request) {
        String key = "idemp:refund:sta:" + request.getRefundNo();
        return RedisIdempotentUtil.execute(key, () -> {
            log.info("[IdempotentScenario] 静态门面防重处理退款: refundNo={}, amount={}",
                    request.getRefundNo(), request.getRefundAmount());
            successProcessCount.incrementAndGet();

            return RefundVO.builder()
                    .refundNo(request.getRefundNo())
                    .refundAmount(request.getRefundAmount())
                    .success(true)
                    .message("静态门面退款核销成功")
                    .build();
        });
    }

    /**
     * 兼容存量单测方法
     */
    @RedisIdempotent(key = "'idemp:order:' + #orderId", expire = "30s", deleteOnFailure = true)
    public String processOrder(String orderId, boolean shouldFail) {
        PaymentCallbackVO vo = simulateFailureAndRetry(PaymentFailureRetryRequest.builder()
                .paymentNo(orderId)
                .amount(BigDecimal.TEN)
                .shouldFail(shouldFail)
                .build());
        return "SUCCESS_" + orderId;
    }

    /**
     * 兼容存量单测编程式支付方法
     */
    public String executeProgrammaticPayment(String paymentNo, BigDecimal amount) {
        RefundVO vo = executeRefundTemplate(RefundRequest.builder()
                .refundNo(paymentNo)
                .refundAmount(amount)
                .build());
        return "PAID_" + paymentNo;
    }

    /**
     * 兼容存量单测静态退款方法
     */
    public String executeStaticRefund(String refundNo, BigDecimal amount) {
        RefundVO vo = executeRefundStatic(RefundRequest.builder()
                .refundNo(refundNo)
                .refundAmount(amount)
                .build());
        return "REFUNDED_" + refundNo;
    }

    public <T, E extends Throwable> T executeProgrammatic(String key, Duration timeout, IdempotentSupplier<T, E> supplier) throws E {
        return redisIdempotentTemplate.execute(key, timeout, supplier);
    }

    public int getSuccessProcessCount() {
        return successProcessCount.get();
    }

    public int getFailureProcessCount() {
        return failureProcessCount.get();
    }

    public void resetCounts() {
        successProcessCount.set(0);
        failureProcessCount.set(0);
    }
}
