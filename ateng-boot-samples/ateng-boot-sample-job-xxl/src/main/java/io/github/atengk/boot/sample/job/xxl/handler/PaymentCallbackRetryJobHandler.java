package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.context.XxlJobHelper;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 支付结果回调通知瞬态异常本地退避重试任务处理器
 *
 * <p>演示纯独立单注解 {@link AtengXxlJob}（无需同时标注官方 {@code @XxlJob}）在执行器自动扫描注册与切面重试链路：
 * 当调用第三方商户回调网关发生瞬态网络抖动时，触发最多 2 次本地退避重试（间隔 100ms），第 3 次成功自愈且不触发误告警；
 * 当模拟持续故障耗尽全部重试次数时，保留原始异常原因链并收敛触发单次告警。</p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Component
public class PaymentCallbackRetryJobHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackRetryJobHandler.class);

    private final ConcurrentMap<String, AtomicInteger> attemptCounters = new ConcurrentHashMap<>();
    private final AtomicInteger totalExecutions = new AtomicInteger();

    /**
     * 独立单注解声明的支付回调补偿任务：首次失败 + 最多 2 次本地退避重试 (共 3 次尝试)
     */
    @AtengXxlJob(value = "paymentCallbackRetryJob", timeout = "5s", retryCount = 2, retryInterval = "100ms")
    public void executePaymentCallbackRetry() {
        String param = XxlJobHelper.getJobParam();
        int failTimes = resolveFailTimes(param);
        String runKey = resolveRunKey(param);
        int attempt = attemptCounters.computeIfAbsent(runKey, k -> new AtomicInteger()).incrementAndGet();
        totalExecutions.incrementAndGet();

        log.info(">>>>>> [PaymentCallbackRetryJobHandler] 执行支付回调推送 runKey={}, 第 {} 次尝试, failTimes={}",
                runKey, attempt, failTimes);

        if (attempt <= failTimes) {
            // retryCount=2 最多执行 3 次尝试；达到或超过第 3 次时清理计数器防止内存残留
            if (attempt >= 3) {
                attemptCounters.remove(runKey);
            }
            String errMsg = failTimes > 2
                    ? "商户回调网关持续不可用 (attempt-" + attempt + ")"
                    : "商户回调网关瞬态抖动 (attempt-" + attempt + ")";
            throw new IllegalStateException(errMsg);
        }

        attemptCounters.remove(runKey);
        XxlJobHelper.handleSuccess("支付回调经本地退避重试第 " + attempt + " 次自愈成功");
    }

    private int resolveFailTimes(String param) {
        if (param == null || param.isBlank()) {
            return 2;
        }
        for (String segment : param.split(",")) {
            String trimmed = segment.trim();
            if (trimmed.startsWith("failTimes=")) {
                String value = trimmed.substring("failTimes=".length()).trim();
                try {
                    return Math.max(0, Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("非法 failTimes 参数值: " + value, e);
                }
            }
        }
        return 2;
    }

    private String resolveRunKey(String param) {
        if (param != null && !param.isBlank()) {
            return param;
        }
        XxlJobContext context = XxlJobContext.getXxlJobContext();
        return context != null ? ("log-" + context.getLogId()) : "default-run";
    }

    public int getTotalExecutions() {
        return totalExecutions.get();
    }

    public void reset() {
        attemptCounters.clear();
        totalExecutions.set(0);
    }
}
