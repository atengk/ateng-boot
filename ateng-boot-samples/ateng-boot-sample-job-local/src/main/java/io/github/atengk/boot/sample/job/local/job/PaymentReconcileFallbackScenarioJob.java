package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 第三方支付对账硬超时熔断与方法级降级补偿场景任务
 * <p>
 * 演示特性：{@link LocalJob#timeout()} 硬超时熔断 + {@link LocalJob#fallbackMethod()} 同 Bean 降级兜底补偿。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Component
public class PaymentReconcileFallbackScenarioJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconcileFallbackScenarioJob.class);

    private final AtomicBoolean simulateTimeout = new AtomicBoolean(false);

    /**
     * 执行支付渠道对账
     *
     * @param context 任务上下文
     * @return 对账结果
     * @throws InterruptedException 超时中断异常
     */
    @LocalJob(
            name = "paymentReconcileFallbackJob",
            description = "第三方支付对账硬超时熔断与降级补偿任务",
            fixedDelay = "1h",
            initialDelay = "1h",
            timeout = "100ms",
            fallbackMethod = "onReconcileTimeoutFallback"
    )
    public JobResult reconcilePaymentGateway(JobContext context) throws InterruptedException {
        context.log("开始拉取第三方支付渠道流水执行对账...");
        Object paramFlag = context.getParams().get("simulateTimeout");
        boolean shouldTimeout = simulateTimeout.get() || Boolean.parseBoolean(String.valueOf(paramFlag));
        if (shouldTimeout) {
            log.warn("paymentReconcileFallbackJob 模拟支付网关拉取耗时过长触发超时熔断...");
            Thread.sleep(400);
        }
        context.log("第三方支付对账完成，核销流水 320 笔");
        return JobResult.success("支付渠道对账完成，核销 320 笔");
    }

    /**
     * 同 Bean 超时降级补偿方法
     *
     * @param context 任务上下文
     * @param cause   超时触发异常
     * @return 降级兜底结果
     */
    public JobResult onReconcileTimeoutFallback(JobContext context, Throwable cause) {
        log.warn("paymentReconcileFallbackJob 超时触发降级补偿，已转入 T+1 异步补账队列: cause={}",
                cause != null ? cause.getMessage() : "unknown");
        return JobResult.success("支付网关超时已触发降级补偿，任务 [" + context.getJobName() + "] 已切入 T+1 异步补账队列");
    }

    /**
     * 设置是否模拟网关超时
     *
     * @param simulate 是否模拟超时
     */
    public void setSimulateTimeout(boolean simulate) {
        this.simulateTimeout.set(simulate);
    }
}
