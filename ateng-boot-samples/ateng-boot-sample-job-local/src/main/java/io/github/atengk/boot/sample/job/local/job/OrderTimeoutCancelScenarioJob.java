package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单超时未支付自动关单与库存回补业务场景任务
 * <p>
 * 演示特性：FixedDelay 固定延迟调度、硬超时熔断中断、指数退避重试与失败告警闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Component
public class OrderTimeoutCancelScenarioJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutCancelScenarioJob.class);

    private final AtomicInteger attemptCount = new AtomicInteger();
    private final AtomicBoolean simulateTimeout = new AtomicBoolean(false);

    /**
     * 执行订单超时关单逻辑
     *
     * @param context 任务上下文
     * @return 任务执行结果
     * @throws InterruptedException 当被调度引擎硬超时熔断中断时抛出
     */
    @LocalJob(
            name = "orderTimeoutCancelJob",
            description = "订单超时自动关单与库存回补任务",
            fixedDelay = "10s",
            initialDelay = "1h",
            timeout = "300ms",
            maxRetries = 2,
            retryInterval = "50ms"
    )
    public JobResult executeOrderTimeoutCancel(JobContext context) throws InterruptedException {
        int attempt = attemptCount.incrementAndGet();
        context.log("开始扫描超时未支付订单，当前尝试次数: " + attempt);

        if (simulateTimeout.get()) {
            log.warn("orderTimeoutCancelJob 模拟下游支付网关响应迟钝，进入等待导致超时 (attempt={})...", attempt);
            Thread.sleep(1000);
        }

        context.log("成功关闭超时订单 5 笔，库存释放完成");
        log.info("orderTimeoutCancelJob executed successfully, attempt={}, traceId={}", attempt, context.getTraceId());
        return JobResult.success("扫描并关闭超时订单: 5 笔");
    }

    /**
     * 设置是否模拟超时熔断
     *
     * @param simulate 是否模拟
     */
    public void setSimulateTimeout(boolean simulate) {
        this.simulateTimeout.set(simulate);
    }

    /**
     * 获取累计调度尝试次数（包含重试）
     *
     * @return 尝试次数
     */
    public int getAttemptCount() {
        return attemptCount.get();
    }

    /**
     * 重置调度尝试计数器
     */
    public void resetAttemptCount() {
        attemptCount.set(0);
    }
}
