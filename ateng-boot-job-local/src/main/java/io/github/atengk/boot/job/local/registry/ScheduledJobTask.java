package io.github.atengk.boot.job.local.registry;

import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 受管定时任务运行时封装包装器
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class ScheduledJobTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobTask.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<Thread> executingThread = new AtomicReference<>();

    @Getter
    private final JobDefinition definition;

    @Getter
    private final JobHandler handler;

    @Getter
    @Setter
    private volatile JobStatus status;

    @Getter
    @Setter
    private volatile ScheduledFuture<?> scheduledFuture;

    @Getter
    @Setter
    private volatile Trigger trigger;

    @Getter
    private volatile Instant lastExecutionTime;

    @Getter
    private volatile Long lastDurationMs;

    @Getter
    private volatile String lastStatus;

    @Getter
    private volatile String lastResultMsg;

    public ScheduledJobTask(JobDefinition definition, JobHandler handler) {
        this.definition = definition;
        this.handler = handler;
        this.status = definition.isEnabled() ? JobStatus.RUNNING : JobStatus.PAUSED;
        this.trigger = buildTrigger(definition);
    }

    /**
     * 触发定时周期执行
     */
    @Override
    public void run() {
        if (status != JobStatus.RUNNING) {
            log.debug("Job [{}] is currently paused or stopped, skipping scheduled execution", definition.getJobName());
            return;
        }
        executeInternal(TriggerType.CRON.equals(definition.getTriggerType()) ? TriggerType.CRON
                : (definition.getFixedRate() != null ? TriggerType.FIXED_RATE : TriggerType.FIXED_DELAY), Collections.emptyMap());
    }

    /**
     * 手动触发执行一次
     *
     * @param params 业务自定义入参
     * @return 执行产物结果
     */
    public JobResult triggerOnce(Map<String, Object> params) {
        return executeInternal(TriggerType.MANUAL, params);
    }

    /**
     * 执行任务核心管道（集成四阶并发重叠策略）
     */
    protected JobResult executeInternal(TriggerType triggerType, Map<String, Object> params) {
        OverlappingStrategy strategy = definition.getStrategy() != null ? definition.getStrategy() : OverlappingStrategy.DISCARD;
        String jobName = definition.getJobName();

        switch (strategy) {
            case DISCARD:
                if (!lock.tryLock()) {
                    log.info("Job [{}] execution skipped due to overlapping DISCARD policy (previous execution is still running)", jobName);
                    this.lastStatus = "DISCARDED";
                    this.lastResultMsg = "Skipped due to overlapping DISCARD policy";
                    return JobResult.builder().success(false).message("Skipped due to overlapping DISCARD policy").build();
                }
                try {
                    return doExecute(triggerType, params);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }

            case QUEUE:
                lock.lock();
                try {
                    return doExecute(triggerType, params);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }

            case CONCURRENT:
                return doExecute(triggerType, params);

            case INTERRUPT:
                Thread previousThread = executingThread.getAndSet(Thread.currentThread());
                if (previousThread != null && previousThread.isAlive() && previousThread != Thread.currentThread()) {
                    log.warn("Job [{}] interrupting previous active thread [{}] due to INTERRUPT policy", jobName, previousThread.getName());
                    previousThread.interrupt();
                }
                lock.lock();
                try {
                    return doExecute(triggerType, params);
                } finally {
                    executingThread.compareAndSet(Thread.currentThread(), null);
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }

            default:
                throw new IllegalStateException("Unsupported overlapping strategy: " + strategy);
        }
    }

    /**
     * 实际调用处理器执行任务并计算统计指标
     */
    protected JobResult doExecute(TriggerType triggerType, Map<String, Object> params) {
        Instant startTime = Instant.now();
        this.lastExecutionTime = startTime;
        String jobName = definition.getJobName();

        JobContext context = JobContext.builder()
                .jobName(jobName)
                .triggerType(triggerType)
                .fireTime(startTime)
                .params(params != null ? params : Collections.emptyMap())
                .build();

        try {
            log.debug("Executing job [{}] triggered by [{}]", jobName, triggerType);
            Object result = handler.execute(context);
            long duration = java.time.Duration.between(startTime, Instant.now()).toMillis();
            this.lastDurationMs = duration;
            this.lastStatus = "SUCCESS";

            String resultMsg = extractResultMessage(result);
            this.lastResultMsg = resultMsg;
            return JobResult.success(resultMsg, result);
        } catch (Throwable ex) {
            long duration = java.time.Duration.between(startTime, Instant.now()).toMillis();
            this.lastDurationMs = duration;
            this.lastStatus = "FAILED";
            this.lastResultMsg = ex.getMessage();
            log.error("Execution failed for job [{}] with exception: {}", jobName, ex.getMessage(), ex);
            return JobResult.failure(ex.getMessage());
        }
    }

    private String extractResultMessage(Object result) {
        if (result == null) {
            return "SUCCESS";
        }
        if (result instanceof JobResult jobResult) {
            return jobResult.getMessage() != null ? jobResult.getMessage() : (jobResult.isSuccess() ? "SUCCESS" : "FAILED");
        }
        if (result instanceof String str) {
            return str;
        }
        return result.toString();
    }

    /**
     * 转换为任务信息视图对象
     *
     * @return JobInfo
     */
    public JobInfo toJobInfo() {
        return JobInfo.builder()
                .jobName(definition.getJobName())
                .triggerType(definition.getTriggerType())
                .status(this.status)
                .cron(definition.getCron())
                .fixedRate(definition.getFixedRate())
                .fixedDelay(definition.getFixedDelay())
                .initialDelay(definition.getInitialDelay())
                .strategy(definition.getStrategy())
                .timeout(definition.getTimeout())
                .maxRetries(definition.getMaxRetries())
                .clusterLock(definition.isClusterLock())
                .description(definition.getDescription())
                .lastExecutionTime(this.lastExecutionTime)
                .lastDurationMs(this.lastDurationMs)
                .lastStatus(this.lastStatus)
                .lastResultMsg(this.lastResultMsg)
                .build();
    }

    /**
     * 根据任务定义构建对应的 Trigger
     */
    public static Trigger buildTrigger(JobDefinition definition) {
        if (definition.getCron() != null && !definition.getCron().isBlank()) {
            return new CronTrigger(definition.getCron());
        }
        if (definition.getFixedRate() != null) {
            PeriodicTrigger periodicTrigger = new PeriodicTrigger(definition.getFixedRate());
            periodicTrigger.setFixedRate(true);
            if (definition.getInitialDelay() != null) {
                periodicTrigger.setInitialDelay(definition.getInitialDelay());
            }
            return periodicTrigger;
        }
        if (definition.getFixedDelay() != null) {
            PeriodicTrigger periodicTrigger = new PeriodicTrigger(definition.getFixedDelay());
            periodicTrigger.setFixedRate(false);
            if (definition.getInitialDelay() != null) {
                periodicTrigger.setInitialDelay(definition.getInitialDelay());
            }
            return periodicTrigger;
        }
        throw new IllegalArgumentException(
                "Job [" + definition.getJobName() + "] must specify either cron, fixedRate, or fixedDelay"
        );
    }
}
