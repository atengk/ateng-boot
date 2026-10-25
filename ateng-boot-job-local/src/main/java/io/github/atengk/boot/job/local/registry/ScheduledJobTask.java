package io.github.atengk.boot.job.local.registry;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.core.util.RetryUtil.RetryConfig;
import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.storage.JobLogStorageProvider;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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

    private final ThreadPoolTaskScheduler taskScheduler;
    private final AsyncTaskExecutor workerExecutor;
    private final JobLogStorageProvider logStorageProvider;
    private final AtomicInteger activeExecutions = new AtomicInteger(0);

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

    @Getter
    private volatile String lastTraceId;

    public ScheduledJobTask(JobDefinition definition, JobHandler handler) {
        this(definition, handler, null, null, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler, ThreadPoolTaskScheduler taskScheduler) {
        this(definition, handler, taskScheduler, null, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor) {
        this(definition, handler, taskScheduler, workerExecutor, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobLogStorageProvider logStorageProvider) {
        this.definition = definition;
        this.handler = handler;
        this.taskScheduler = taskScheduler;
        this.workerExecutor = workerExecutor;
        this.logStorageProvider = logStorageProvider;
        this.status = definition.getStatus() != null ? definition.getStatus()
                : (definition.isEnabled() ? JobStatus.RUNNING : JobStatus.PAUSED);
        this.trigger = buildTrigger(definition);
    }

    /**
     * 判断当前任务是否有正在运行中的执行实例
     *
     * @return true 表示存在活跃执行
     */
    public boolean isExecuting() {
        return activeExecutions.get() > 0;
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
        TriggerType triggerType = TriggerType.CRON.equals(definition.getTriggerType()) ? TriggerType.CRON
                : (definition.getFixedRate() != null ? TriggerType.FIXED_RATE : TriggerType.FIXED_DELAY);
        try {
            executeInternal(triggerType, Collections.emptyMap());
        } catch (JobTimeoutException ex) {
            log.warn("Job [{}] scheduled execution timed out: {}", definition.getJobName(), ex.getMessage());
        } catch (Throwable ex) {
            log.error("Job [{}] scheduled execution failed: {}", definition.getJobName(), ex.getMessage(), ex);
        }
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
     * 执行任务核心管道（集成四阶并发重叠策略、MDC 链路追踪与全上下文透传防御）
     */
    protected JobResult executeInternal(TriggerType triggerType, Map<String, Object> params) {
        OverlappingStrategy strategy = definition.getStrategy() != null ? definition.getStrategy() : OverlappingStrategy.DISCARD;
        String jobName = definition.getJobName();

        // 1. 抓取调用方当前线程上下文现场
        String previousTraceId = MDC.get("traceId");
        String previousUser = UserContextHolder.getExplicit();
        TenantContextHolder.TenantSnapshot previousTenant = TenantContextHolder.capturePrevious();

        // 2. 生成无锁 32 位 UUID 注入 MDC，挂载租户与系统用户
        String traceId = null;
        if (params != null && params.containsKey("traceId")) {
            traceId = String.valueOf(params.get("traceId"));
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = IdUtil.fastSimpleUUID();
        }
        MDC.put("traceId", traceId);

        if (params != null && params.containsKey("userId")) {
            UserContextHolder.set(String.valueOf(params.get("userId")));
        } else if (UserContextHolder.get() == null) {
            UserContextHolder.set("SYSTEM");
        }

        if (params != null && params.containsKey("tenantId")) {
            TenantContextHolder.setTenantId(String.valueOf(params.get("tenantId")));
        }

        activeExecutions.incrementAndGet();
        try {
            switch (strategy) {
                case DISCARD:
                    if (!lock.tryLock()) {
                        log.info("Job [{}] execution skipped due to overlapping DISCARD policy (previous execution is still running)", jobName);
                        this.lastStatus = "DISCARDED";
                        this.lastResultMsg = "Skipped due to overlapping DISCARD policy";
                        saveExecutionRecord(traceId, triggerType, Instant.now(), Instant.now(), 0L, "DISCARDED",
                                "Skipped due to overlapping DISCARD policy", null, null, 0, Collections.emptyList());
                        return JobResult.builder().success(false).message("Skipped due to overlapping DISCARD policy").build();
                    }
                    try {
                        return doExecute(triggerType, params, traceId);
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }

                case QUEUE:
                    lock.lock();
                    try {
                        return doExecute(triggerType, params, traceId);
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }

                case CONCURRENT:
                    return doExecute(triggerType, params, traceId);

                case INTERRUPT:
                    Thread previousThread = executingThread.getAndSet(Thread.currentThread());
                    if (previousThread != null && previousThread.isAlive() && previousThread != Thread.currentThread()) {
                        log.warn("Job [{}] interrupting previous active thread [{}] due to INTERRUPT policy", jobName, previousThread.getName());
                        previousThread.interrupt();
                    }
                    lock.lock();
                    try {
                        return doExecute(triggerType, params, traceId);
                    } finally {
                        executingThread.compareAndSet(Thread.currentThread(), null);
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }

                default:
                    throw new IllegalStateException("Unsupported overlapping strategy: " + strategy);
            }
        } finally {
            activeExecutions.decrementAndGet();
            // 3. 严格清理现场或精准恢复原现场，100% 杜绝线程池污染
            if (previousTraceId != null) {
                MDC.put("traceId", previousTraceId);
            } else {
                MDC.remove("traceId");
            }
            if (previousUser != null) {
                UserContextHolder.set(previousUser);
            } else {
                UserContextHolder.clear();
            }
            TenantContextHolder.restore(previousTenant);
        }
    }

    /**
     * 实际调用处理器执行任务并计算统计指标（集成超时中断与退避重试）
     */
    protected JobResult doExecute(TriggerType triggerType, Map<String, Object> params, String traceId) {
        Instant startTime = Instant.now();
        this.lastExecutionTime = startTime;
        this.lastTraceId = traceId;
        String jobName = definition.getJobName();

        JobContext context = JobContext.builder()
                .jobName(jobName)
                .triggerType(triggerType)
                .fireTime(startTime)
                .params(params != null ? params : Collections.emptyMap())
                .traceId(traceId)
                .build();

        AtomicInteger retryCount = new AtomicInteger(0);

        try {
            log.debug("Executing job [{}] triggered by [{}] with traceId [{}]", jobName, triggerType, traceId);
            Object result = executeWithRetryAndTimeout(context, retryCount);

            long duration = Duration.between(startTime, Instant.now()).toMillis();
            this.lastDurationMs = duration;
            this.lastStatus = "SUCCESS";

            String resultMsg = extractResultMessage(result);
            this.lastResultMsg = resultMsg;
            saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "SUCCESS",
                    resultMsg, null, null, retryCount.get(), context.getLogs());
            return JobResult.success(resultMsg, result);
        } catch (JobTimeoutException ex) {
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            this.lastDurationMs = duration;
            this.lastStatus = "TIMEOUT";
            this.lastResultMsg = ex.getMessage();
            log.error("Execution timed out for job [{}] after {}: {}", jobName, ex.getTimeout(), ex.getMessage());
            saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "TIMEOUT",
                    ex.getMessage(), ex.getMessage(), getStackTraceString(ex), retryCount.get(), context.getLogs());
            throw ex;
        } catch (Throwable ex) {
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            this.lastDurationMs = duration;
            this.lastStatus = "FAILED";
            this.lastResultMsg = ex.getMessage();
            log.error("Execution failed for job [{}] with exception: {}", jobName, ex.getMessage(), ex);
            saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "FAILED",
                    ex.getMessage(), ex.getMessage(), getStackTraceString(ex), retryCount.get(), context.getLogs());
            return JobResult.failure(ex.getMessage());
        }
    }

    private Object executeWithRetryAndTimeout(JobContext context, AtomicInteger retryCount) {
        int maxRetries = definition.getMaxRetries();
        Duration retryInterval = definition.getRetryInterval() != null ? definition.getRetryInterval() : Duration.ofSeconds(1);
        String jobName = definition.getJobName();

        Supplier<Object> supplier = () -> {
            try {
                return executeSingleAttempt(context);
            } catch (Throwable t) {
                sneakyThrow(t);
                return null;
            }
        };

        if (maxRetries > 0) {
            RetryConfig retryConfig = RetryConfig.builder()
                    .maxAttempts(1 + maxRetries)
                    .delay(retryInterval)
                    .multiplier(2.0)
                    .maxDelay(Duration.ofSeconds(60))
                    .onRetry((attempt, error) -> {
                        retryCount.incrementAndGet();
                        log.warn("Job [{}] execution attempt [{}] failed ({}), retrying with exponential backoff...",
                                jobName, attempt, error.getMessage());
                        context.log("Retry attempt " + attempt + " after failure: " + error.getMessage());
                    })
                    .build();
            return RetryUtil.execute(supplier, retryConfig);
        } else {
            return supplier.get();
        }
    }

    private Object executeSingleAttempt(JobContext context) throws Throwable {
        Duration timeout = definition.getTimeout();
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            return executeWithTimeout(context, timeout);
        } else {
            return executeDirectly(context);
        }
    }

    private Object executeDirectly(JobContext context) throws Throwable {
        Thread current = Thread.currentThread();
        executingThread.set(current);
        try {
            return handler.execute(context);
        } finally {
            executingThread.compareAndSet(current, null);
        }
    }

    private Object executeWithTimeout(JobContext context, Duration timeout) throws Throwable {
        String jobName = definition.getJobName();
        AtomicReference<Thread> workerThreadRef = new AtomicReference<>();

        Callable<Object> workerTask = () -> {
            Thread worker = Thread.currentThread();
            workerThreadRef.set(worker);
            executingThread.set(worker);

            String traceId = context.getTraceId();
            if (traceId != null) {
                MDC.put("traceId", traceId);
            }
            if (UserContextHolder.get() == null) {
                UserContextHolder.set("SYSTEM");
            }
            try {
                return handler.execute(context);
            } finally {
                try {
                    MDC.remove("traceId");
                } finally {
                    try {
                        UserContextHolder.clear();
                    } finally {
                        TenantContextHolder.clear();
                    }
                }
                executingThread.compareAndSet(worker, null);
            }
        };

        Future<Object> future = submitTask(workerTask);

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            Thread worker = workerThreadRef.get();
            if (worker != null && worker.isAlive()) {
                worker.interrupt();
            }
            throw new JobTimeoutException(
                    "Job [" + jobName + "] execution timed out after " + timeout,
                    jobName,
                    timeout,
                    te
            );
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            throw cause != null ? cause : ee;
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ie;
        }
    }

    private Future<Object> submitTask(Callable<Object> task) {
        if (workerExecutor != null) {
            return workerExecutor.submit(task);
        }
        if (taskScheduler != null) {
            return taskScheduler.submit(task);
        }
        CompletableFuture<Object> cf = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                cf.complete(task.call());
            } catch (Throwable ex) {
                cf.completeExceptionally(ex);
            }
        }, "ateng-job-worker-fallback");
        t.start();
        return cf;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
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
                .lastTraceId(this.lastTraceId)
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

    private void saveExecutionRecord(String traceId, TriggerType triggerType, Instant fireTime, Instant finishTime,
                                     Long durationMs, String status, String resultMsg, String errorMsg,
                                     String errorStack, int retryCount, List<String> logs) {
        if (logStorageProvider == null) {
            return;
        }
        try {
            JobExecutionRecord record = JobExecutionRecord.builder()
                    .id(IdUtil.fastSimpleUUID())
                    .jobName(definition.getJobName())
                    .traceId(traceId)
                    .triggerType(triggerType)
                    .fireTime(fireTime)
                    .finishTime(finishTime)
                    .durationMs(durationMs)
                    .status(status)
                    .resultMsg(resultMsg)
                    .errorMessage(errorMsg)
                    .errorStack(errorStack)
                    .retryCount(retryCount)
                    .logs(logs != null ? new ArrayList<>(logs) : Collections.emptyList())
                    .build();
            logStorageProvider.saveLog(record);
        } catch (Throwable t) {
            log.warn("Failed to persist execution log for job [{}]: {}", definition.getJobName(), t.getMessage());
        }
    }

    private String getStackTraceString(Throwable t) {
        if (t == null) {
            return null;
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
