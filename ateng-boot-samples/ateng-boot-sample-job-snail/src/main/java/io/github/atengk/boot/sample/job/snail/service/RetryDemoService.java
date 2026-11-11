package io.github.atengk.boot.sample.job.snail.service;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.retry.template.SnailRetryTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式协同重试业务服务示例
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
     * 声明式重试方法：订单支付
     *
     * @param orderId 业务订单编号
     * @param amount  支付金额
     * @return 支付执行结果摘要
     */
    @AtengSnailRetry(scene = "orderPayRetry")
    @Retryable(scene = "orderPayRetry")
    public String payOrder(String orderId, BigDecimal amount) {
        int currentAttempt = attemptCount.incrementAndGet();
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[RetryDemoService] 正在处理支付请求: orderId={}, amount={}, 租户={}, 尝试次数={}",
                orderId, amount, currentTenant, currentAttempt);

        if ("FAIL_ORDER".equals(orderId)) {
            throw new IllegalStateException("模拟支付网关网络超时 [orderId: " + orderId + "]");
        }

        return "PAY_SUCCESS:" + orderId + ":ATTEMPT_" + currentAttempt;
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
