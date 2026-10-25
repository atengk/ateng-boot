package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 示例定时任务组件，演示全场景声明式注解调度
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Component
public class SampleScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(SampleScheduledJobs.class);

    private final AtomicInteger cronExecutionCount = new AtomicInteger();
    private final AtomicInteger timeoutAttemptCount = new AtomicInteger();

    /**
     * 每日定时健康对账 Cron 任务（演示 JobContext 注入、结构化内存日志记录与强类型 JobResult 返回）
     */
    @LocalJob(name = "sampleCronJob", description = "每日定时健康对账 Cron 任务", cron = "0 0 2 * * ?")
    public JobResult executeDailyReconciliation(JobContext context) {
        int count = cronExecutionCount.incrementAndGet();
        context.log("开始执行第 [" + count + "] 次对账数据巡检...");
        context.log("对账批次加载完成，匹配记录数: 1200 条");
        log.info("sampleCronJob executed successfully, traceId={}", context.getTraceId());
        return JobResult.success("对账完成，匹配成功: 1200 条，异常 0 条");
    }

    /**
     * 固定频率心跳采样任务（演示固定频率 fixedRate 与 String 结果返回）
     */
    @LocalJob(name = "sampleFixedRateJob", description = "固定频率心跳采样任务", fixedRate = "5s", initialDelay = "1h")
    public String executeHeartbeatSampling() {
        log.info("executeHeartbeatSampling: 系统指标正常，CPU使用率 12%");
        return "心跳正常，CPU: 12%, 内存: 34%";
    }

    /**
     * 固定间隔消费任务（演示固定延迟 fixedDelay 与 void 返回）
     */
    @LocalJob(name = "sampleFixedDelayJob", description = "固定间隔消费任务", fixedDelay = "10s", initialDelay = "1h")
    public void executeFixedDelayTask() {
        log.info("executeFixedDelayTask: 检查待清理临时文件完成");
    }

    /**
     * 并发丢弃防卫演示任务（演示 OverlappingStrategy.DISCARD）
     */
    @LocalJob(name = "sampleDiscardJob", description = "并发丢弃防卫演示任务",
            strategy = OverlappingStrategy.DISCARD, fixedDelay = "1h", initialDelay = "1h")
    public void executeDiscardJob() throws InterruptedException {
        log.info("sampleDiscardJob running...");
        Thread.sleep(200);
    }

    /**
     * 并发排队防卫演示任务（演示 OverlappingStrategy.QUEUE）
     */
    @LocalJob(name = "sampleQueueJob", description = "并发排队防卫演示任务",
            strategy = OverlappingStrategy.QUEUE, fixedDelay = "1h", initialDelay = "1h")
    public void executeQueueJob() throws InterruptedException {
        log.info("sampleQueueJob running...");
        Thread.sleep(100);
    }

    /**
     * 并发打断前序防卫演示任务（演示 OverlappingStrategy.INTERRUPT）
     */
    @LocalJob(name = "sampleInterruptJob", description = "并发打断前序防卫演示任务",
            strategy = OverlappingStrategy.INTERRUPT, fixedDelay = "1h", initialDelay = "1h")
    public void executeInterruptJob() {
        log.info("sampleInterruptJob running...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            log.info("sampleInterruptJob received interrupt signal as expected");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 硬超时熔断与指数退避重试演示任务
     */
    @LocalJob(name = "sampleTimeoutRetryJob", description = "硬超时熔断与重试演示任务",
            timeout = "300ms", maxRetries = 2, retryInterval = "50ms",
            fixedDelay = "1h", initialDelay = "1h")
    public void executeTimeoutRetryJob() throws InterruptedException {
        int attempt = timeoutAttemptCount.incrementAndGet();
        log.warn("sampleTimeoutRetryJob attempt: {}", attempt);
        Thread.sleep(1000);
    }

    /**
     * 轻量集群互斥防死锁租约任务
     */
    @LocalJob(name = "sampleClusterLockJob", description = "轻量集群互斥防死锁租约任务",
            clusterLock = true, clusterLockLeaseTime = "1m",
            fixedDelay = "1h", initialDelay = "1h")
    public String executeClusterLockJob() {
        log.info("sampleClusterLockJob acquired cluster lock and running");
        return "集群互斥锁获取成功并执行完毕";
    }

    public int getCronExecutionCount() {
        return cronExecutionCount.get();
    }

    public int getTimeoutAttemptCount() {
        return timeoutAttemptCount.get();
    }
}
