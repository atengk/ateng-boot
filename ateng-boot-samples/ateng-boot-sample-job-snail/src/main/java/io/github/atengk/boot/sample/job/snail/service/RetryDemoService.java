package io.github.atengk.boot.sample.job.snail.service;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.retry.template.SnailRetryTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式协同重试业务服务示例
 *
 * <p>演示 {@link AtengSnailRetry} 单注解生产级闭环（{@code scene} 与 {@code value} 别名）、
 * 原生 {@link Retryable} 混用显式优先向后兼容，以及 {@link SnailRetryTemplate} 编程式重试与手动上报。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryDemoService {

    private final SnailRetryTemplate retryTemplate;
    private final AtomicInteger attemptCount = new AtomicInteger(0);

    /**
     * 声明式单注解重试方法：订单支付（仅标注 {@link AtengSnailRetry}，免除双注解冗余）
     *
     * @param orderId 业务订单编号
     * @param amount  支付金额
     * @return 支付执行结果摘要
     */
    @AtengSnailRetry(scene = "orderPayRetry", idempotentSpel = "#p0", bizNo = "#p0")
    public String payOrder(String orderId, BigDecimal amount) {
        int currentAttempt = attemptCount.incrementAndGet();
        String currentTenant = TenantContextHolder.getTenantId();
        String currentUser = UserContextHolder.getExplicit();
        String traceId = MDC.get("traceId");
        log.info("[RetryDemoService] 正在处理支付请求: orderId={}, amount={}, 租户={}, 操作人={}, traceId={}, 尝试次数={}",
                orderId, amount, currentTenant, currentUser, traceId, currentAttempt);

        if ("FAIL_ORDER".equals(orderId)) {
            throw new IllegalStateException("模拟支付网关网络超时 [orderId: " + orderId + "]");
        }

        return "PAY_SUCCESS:" + orderId + ":TENANT_" + currentTenant + ":ATTEMPT_" + currentAttempt;
    }

    /**
     * 声明式单注解（{@code value} 别名）重试方法：订单退款
     *
     * @param refundNo 退款单号
     * @param amount   退款金额
     * @return 退款执行结果摘要
     */
    @AtengSnailRetry(value = "orderRefundRetry", idempotentSpel = "#p0", bizNo = "#p0")
    public String refundOrder(String refundNo, BigDecimal amount) {
        int currentAttempt = attemptCount.incrementAndGet();
        String currentTenant = TenantContextHolder.getTenantId();
        String currentUser = UserContextHolder.getExplicit();
        log.info("[RetryDemoService] 正在处理退款请求: refundNo={}, amount={}, 租户={}, 操作人={}, 尝试次数={}",
                refundNo, amount, currentTenant, currentUser, currentAttempt);

        if ("FAIL_REFUND".equals(refundNo)) {
            throw new IllegalStateException("模拟退款通道瞬时熔断 [refundNo: " + refundNo + "]");
        }

        return "REFUND_SUCCESS:" + refundNo + ":TENANT_" + currentTenant + ":USER_" + currentUser;
    }

    /**
     * 向后兼容演示：同时标注 {@link AtengSnailRetry} 与原生 {@link Retryable} 时，显式 {@link Retryable} 优先
     *
     * @param orderId 业务订单编号
     * @return 通知执行结果
     */
    @AtengSnailRetry(scene = "ignoredFallbackScene", idempotentSpel = "#p0", bizNo = "#p0")
    @Retryable(scene = "legacyDualCompatRetry")
    public String legacyDualCompatNotify(String orderId) {
        attemptCount.incrementAndGet();
        return "LEGACY_COMPAT_OK:" + orderId;
    }

    /**
     * 编程式重试方法：通过 SnailRetryTemplate 执行业务闭包
     *
     * @param orderId 业务订单编号
     * @return 闭包执行结果
     */
    public String executeWithProgrammaticRetry(String orderId) {
        return retryTemplate.executeWithIdempotent("orderPayRetry", orderId, "BIZ-" + orderId, () -> {
            log.info("[RetryDemoService] 编程式重试闭包执行中, orderId={}", orderId);
            if ("FAIL_PROG".equals(orderId)) {
                throw new IllegalStateException("编程式支付通道连接池耗尽 [orderId: " + orderId + "]");
            }
            return "SUCCESS:" + orderId;
        });
    }

    /**
     * 手动失败上报方法：在脱机或异步失败时向 SnailJob 上报
     *
     * @param orderId 业务订单编号
     * @return 上报是否成功
     */
    public boolean reportFailedOrder(String orderId) {
        log.info("[RetryDemoService] 手动上报失败重试任务: orderId={}", orderId);
        return retryTemplate.reportManualRetry("orderPayRetry", getClass().getName(),
                new Object[]{orderId, new BigDecimal("100.00")}, orderId, "BIZ-" + orderId);
    }

    public int getAttemptCount() {
        return attemptCount.get();
    }

    public void resetAttemptCount() {
        attemptCount.set(0);
    }
}
