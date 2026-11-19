package io.github.atengk.boot.job.local.registry;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.core.util.RetryUtil.RetryConfig;
import io.github.atengk.boot.job.local.enums.DiscardReason;
import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.JobTenantMode;
import io.github.atengk.boot.job.local.enums.OverlappingStrategy;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.event.JobDiscardedEvent;
import io.github.atengk.boot.job.local.event.JobFailedEvent;
import io.github.atengk.boot.job.local.event.JobStartingEvent;
import io.github.atengk.boot.job.local.event.JobSuccessEvent;
import io.github.atengk.boot.job.local.event.JobTimeoutEvent;
import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.fallback.LocalJobTimeoutFallbackHandler;
import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.handler.MethodInvokingJobHandler;
import io.github.atengk.boot.job.local.interceptor.LocalJobInterceptor;
import io.github.atengk.boot.job.local.lock.JobClusterLockProvider;
import io.github.atengk.boot.job.local.lock.JobLockHandle;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import io.github.atengk.boot.job.local.storage.JobLogStorageProvider;
import io.github.atengk.boot.job.local.tenant.DefaultTenantProvider;
import io.github.atengk.boot.job.local.tenant.TenantProvider;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final JobClusterLockProvider clusterLockProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantProvider tenantProvider;
    private final JobLocalProperties properties;
    private final Supplier<List<LocalJobInterceptor>> interceptorsProvider;
    private final Supplier<LocalJobTimeoutFallbackHandler> timeoutFallbackHandlerProvider;
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
        this(definition, handler, null, null, null, null, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler, ThreadPoolTaskScheduler taskScheduler) {
        this(definition, handler, taskScheduler, null, null, null, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor) {
        this(definition, handler, taskScheduler, workerExecutor, null, null, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobLogStorageProvider logStorageProvider) {
        this(definition, handler, taskScheduler, workerExecutor, logStorageProvider, null, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobLogStorageProvider logStorageProvider,
                            JobClusterLockProvider clusterLockProvider) {
        this(definition, handler, taskScheduler, workerExecutor, logStorageProvider, clusterLockProvider, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobLogStorageProvider logStorageProvider,
                            JobClusterLockProvider clusterLockProvider,
                            ApplicationEventPublisher eventPublisher) {
        this(definition, handler, taskScheduler, workerExecutor, logStorageProvider, clusterLockProvider, eventPublisher, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobLogStorageProvider logStorageProvider,
                            JobClusterLockProvider clusterLockProvider,
                            ApplicationEventPublisher eventPublisher,
                            TenantProvider tenantProvider) {
        this(definition, handler, taskScheduler, workerExecutor, logStorageProvider, clusterLockProvider,
                eventPublisher, tenantProvider, null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobLogStorageProvider logStorageProvider,
                            JobClusterLockProvider clusterLockProvider,
                            ApplicationEventPublisher eventPublisher,
                            TenantProvider tenantProvider,
                            JobLocalProperties properties) {
        this(definition, handler, taskScheduler, workerExecutor, logStorageProvider, clusterLockProvider,
                eventPublisher, tenantProvider, properties, Collections.emptyList(), null);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobLogStorageProvider logStorageProvider,
                            JobClusterLockProvider clusterLockProvider,
                            ApplicationEventPublisher eventPublisher,
                            TenantProvider tenantProvider,
                            JobLocalProperties properties,
                            List<LocalJobInterceptor> interceptors,
                            LocalJobTimeoutFallbackHandler timeoutFallbackHandler) {
        this(definition, handler, taskScheduler, workerExecutor, logStorageProvider, clusterLockProvider,
                eventPublisher, tenantProvider, properties,
                () -> normalizeInterceptors(interceptors),
                () -> timeoutFallbackHandler);
    }

    public ScheduledJobTask(JobDefinition definition, JobHandler handler,
                            ThreadPoolTaskScheduler taskScheduler,
                            AsyncTaskExecutor workerExecutor,
                            JobLogStorageProvider logStorageProvider,
                            JobClusterLockProvider clusterLockProvider,
                            ApplicationEventPublisher eventPublisher,
                            TenantProvider tenantProvider,
                            JobLocalProperties properties,
                            Supplier<List<LocalJobInterceptor>> interceptorsProvider,
                            Supplier<LocalJobTimeoutFallbackHandler> timeoutFallbackHandlerProvider) {
        this.definition = definition;
        this.handler = handler;
        this.taskScheduler = taskScheduler;
        this.workerExecutor = workerExecutor;
        this.logStorageProvider = logStorageProvider;
        this.clusterLockProvider = clusterLockProvider;
        this.eventPublisher = eventPublisher;
        this.tenantProvider = tenantProvider != null ? tenantProvider : new DefaultTenantProvider();
        this.properties = properties;
        this.interceptorsProvider = interceptorsProvider;
        this.timeoutFallbackHandlerProvider = timeoutFallbackHandlerProvider;
        this.status = definition.getStatus() != null ? definition.getStatus()
                : (definition.isEnabled() ? JobStatus.RUNNING : JobStatus.PAUSED);
        this.trigger = buildTrigger(definition);
    }

    private static List<LocalJobInterceptor> normalizeInterceptors(List<LocalJobInterceptor> interceptors) {
        if (interceptors == null || interceptors.isEmpty()) {
            return Collections.emptyList();
        }
        List<LocalJobInterceptor> sorted = new ArrayList<>(interceptors);
        AnnotationAwareOrderComparator.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }

    private List<LocalJobInterceptor> getEffectiveInterceptors() {
        List<LocalJobInterceptor> resolved = interceptorsProvider != null ? interceptorsProvider.get() : Collections.emptyList();
        return resolved != null ? resolved : Collections.emptyList();
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
     * 获取当前任务正在并发执行的活跃 Worker 实例数
     *
     * @return 活跃并发数
     */
    public int getActiveCount() {
        return activeExecutions.get();
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

        if (definition.getTenantMode() == JobTenantMode.IGNORE) {
            TenantContextHolder.setIgnoreTenant(true);
        } else if (params != null && params.containsKey("tenantId") && params.get("tenantId") != null) {
            TenantContextHolder.setTenantId(String.valueOf(params.get("tenantId")));
        }

        publishEvent(new JobStartingEvent(this, jobName, triggerType, traceId, params));

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
                        publishEvent(new JobDiscardedEvent(this, jobName, triggerType, traceId,
                                DiscardReason.OVERLAPPING_DISCARD, "Skipped due to overlapping DISCARD policy"));
                        return JobResult.builder().success(false).message("Skipped due to overlapping DISCARD policy").build();
                    }
                    return executeUnderLock(triggerType, params, traceId);

                case QUEUE:
                    try {
                        lock.lockInterruptibly();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Job [{}] interrupted while waiting for lock in QUEUE policy", jobName);
                        return JobResult.failure("Interrupted while waiting in QUEUE policy");
                    }
                    return executeUnderLock(triggerType, params, traceId);

                case CONCURRENT:
                    return executeWithClusterLock(triggerType, params, traceId);

                case INTERRUPT:
                    Thread runningThread = executingThread.get();
                    if (runningThread != null && runningThread.isAlive() && runningThread != Thread.currentThread()) {
                        log.warn("Job [{}] interrupting previous active thread [{}] due to INTERRUPT policy", jobName, runningThread.getName());
                        runningThread.interrupt();
                    }
                    try {
                        lock.lockInterruptibly();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Job [{}] interrupted while waiting for lock in INTERRUPT policy", jobName);
                        return JobResult.failure("Interrupted while waiting in INTERRUPT policy");
                    }
                    return executeUnderLock(triggerType, params, traceId);

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
     * 在细粒度条带锁保护下安全执行任务并确保锁确定性释放
     */
    private JobResult executeUnderLock(TriggerType triggerType, Map<String, Object> params, String traceId) {
        try {
            return executeWithClusterLock(triggerType, params, traceId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 挂载轻量集群互斥锁防重门禁
     */
    protected JobResult executeWithClusterLock(TriggerType triggerType, Map<String, Object> params, String traceId) {
        if (definition.isClusterLock() && clusterLockProvider != null) {
            Duration leaseTime = resolveClusterLockLeaseTime();
            Duration minHoldTime = resolveClusterLockMinHoldTime();
            String jobName = definition.getJobName();
            JobLockHandle lockHandle = clusterLockProvider.tryLock(jobName, leaseTime);
            if (lockHandle == null || !lockHandle.isAcquired()) {
                log.info("Job [{}] skipped execution because cluster lock is currently held by another node", jobName);
                this.lastStatus = "SKIPPED_CLUSTER_LOCKED";
                this.lastResultMsg = "Skipped: cluster lock held by another node";
                saveExecutionRecord(traceId, triggerType, Instant.now(), Instant.now(), 0L, "SKIPPED_CLUSTER_LOCKED",
                        "Skipped: cluster lock held by another node", null, null, 0, Collections.emptyList());
                publishEvent(new JobDiscardedEvent(this, jobName, triggerType, traceId,
                        DiscardReason.CLUSTER_LOCKED, "Skipped: cluster lock held by another node"));
                return JobResult.builder().success(true).message("Skipped: cluster lock held by another node").build();
            }
            try {
                return doExecute(triggerType, params, traceId);
            } finally {
                if (minHoldTime != null && !minHoldTime.isZero() && !minHoldTime.isNegative()) {
                    clusterLockProvider.unlock(lockHandle, minHoldTime);
                } else {
                    clusterLockProvider.unlock(lockHandle);
                }
            }
        }
        return doExecute(triggerType, params, traceId);
    }

    private Duration resolveClusterLockLeaseTime() {
        if (definition.getClusterLockLeaseTime() != null) {
            return definition.getClusterLockLeaseTime();
        }
        if (properties != null && properties.getClusterLock() != null
                && properties.getClusterLock().getDefaultLeaseTime() != null) {
            return properties.getClusterLock().getDefaultLeaseTime();
        }
        return Duration.ofMinutes(5);
    }

    private Duration resolveClusterLockMinHoldTime() {
        if (definition.getClusterLockMinHoldTime() != null) {
            return definition.getClusterLockMinHoldTime();
        }
        if (properties != null && properties.getClusterLock() != null
                && properties.getClusterLock().getDefaultMinHoldTime() != null) {
            return properties.getClusterLock().getDefaultMinHoldTime();
        }
        return Duration.ZERO;
    }

    /**
     * 实际调用处理器执行任务并计算统计指标（外层挂载三阶段拦截器，内层支持两级超时降级与信号量多租户并发遍历）
     */
    protected JobResult doExecute(TriggerType triggerType, Map<String, Object> params, String traceId) {
        Instant startTime = Instant.now();
        this.lastExecutionTime = startTime;
        this.lastTraceId = traceId;
        String jobName = definition.getJobName();

        JobContext outerContext = buildExecutionContext(triggerType, params, traceId, startTime, resolveInitialTenantId(params));
        List<LocalJobInterceptor> activeInterceptors = getEffectiveInterceptors();
        if (activeInterceptors.isEmpty()) {
            return executeDispatchedBody(triggerType, params, traceId, startTime, outerContext).result();
        }

        int passedIndex = -1;
        JobResult finalResult = null;
        Throwable executionThrowable = null;
        try {
            // 1. 前置拦截正序判定
            for (int i = 0; i < activeInterceptors.size(); i++) {
                LocalJobInterceptor interceptor = activeInterceptors.get(i);
                boolean proceed;
                try {
                    proceed = interceptor.preHandle(outerContext);
                } catch (Throwable preEx) {
                    executionThrowable = preEx;
                    long duration = Duration.between(startTime, Instant.now()).toMillis();
                    this.lastDurationMs = duration;
                    this.lastStatus = "FAILED";
                    this.lastResultMsg = preEx.getMessage();
                    log.error("LocalJobInterceptor [{}] preHandle failed for job [{}]: {}",
                            interceptor.getClass().getSimpleName(), jobName, preEx.getMessage(), preEx);
                    saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "FAILED",
                            preEx.getMessage(), preEx.getMessage(), getStackTraceString(preEx), 0, outerContext.getLogs());
                    publishEvent(new JobFailedEvent(this, jobName, triggerType, traceId, duration, 0, preEx, preEx.getMessage()));
                    finalResult = JobResult.failure(preEx.getMessage());
                    return finalResult;
                }
                if (!proceed) {
                    long duration = Duration.between(startTime, Instant.now()).toMillis();
                    String rejectMsg = "Skipped: rejected by interceptor [" + interceptor.getClass().getSimpleName() + "]";
                    this.lastDurationMs = duration;
                    this.lastStatus = "DISCARDED";
                    this.lastResultMsg = rejectMsg;
                    saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "DISCARDED",
                            rejectMsg, null, null, 0, outerContext.getLogs());
                    publishEvent(new JobDiscardedEvent(this, jobName, triggerType, traceId,
                            DiscardReason.INTERCEPTOR_REJECTED, rejectMsg));
                    finalResult = JobResult.builder().success(false).message(rejectMsg).build();
                    return finalResult;
                }
                passedIndex = i;
            }

            // 2. 分派单次或全租户遍历执行（内部已完成状态记录与事件发布）
            ExecutionOutcome outcome = executeDispatchedBody(triggerType, params, traceId, startTime, outerContext);
            finalResult = outcome.result();
            executionThrowable = outcome.exception();

            // 3. 正常或降级完成后正序执行 postHandle（隔离后置处理异常，防止重复落盘终态事件冲突）
            if (executionThrowable == null) {
                for (LocalJobInterceptor interceptor : activeInterceptors) {
                    try {
                        interceptor.postHandle(outerContext, finalResult);
                    } catch (Throwable postEx) {
                        if (executionThrowable == null) {
                            executionThrowable = postEx;
                        }
                        log.warn("LocalJobInterceptor [{}] postHandle threw exception for job [{}]: {}",
                                interceptor.getClass().getSimpleName(), jobName, postEx.getMessage(), postEx);
                    }
                }
            }
            return finalResult;
        } catch (Throwable ex) {
            executionThrowable = ex;
            throw ex;
        } finally {
            // 4. 终态对已通过 preHandle 的拦截器严格逆序执行 afterCompletion
            triggerAfterCompletion(activeInterceptors, passedIndex, outerContext, finalResult, executionThrowable);
        }
    }

    private ExecutionOutcome executeDispatchedBody(TriggerType triggerType, Map<String, Object> params, String traceId,
                                                   Instant startTime, JobContext outerContext) {
        if (definition.getTenantMode() == JobTenantMode.ITERATE) {
            return new ExecutionOutcome(doExecuteIterate(triggerType, params, traceId, startTime), null);
        }
        return doExecuteSingle(triggerType, outerContext, startTime);
    }

    private ExecutionOutcome doExecuteSingle(TriggerType triggerType, JobContext context, Instant startTime) {
        String jobName = definition.getJobName();
        String traceId = context.getTraceId();
        AtomicInteger retryCount = new AtomicInteger(0);

        try {
            log.debug("Executing job [{}] triggered by [{}] with traceId [{}]", jobName, triggerType, traceId);
            Object result = executeWithRetryAndTimeout(context, retryCount, true);

            long duration = Duration.between(startTime, Instant.now()).toMillis();
            this.lastDurationMs = duration;

            if (result instanceof JobResult jobResult && !jobResult.isSuccess()) {
                this.lastStatus = "FAILED";
                String resultMsg = extractResultMessage(jobResult);
                this.lastResultMsg = resultMsg;
                saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "FAILED",
                        resultMsg, jobResult.getMessage(), null, retryCount.get(), context.getLogs());
                publishEvent(new JobFailedEvent(this, jobName, triggerType, traceId, duration, retryCount.get(), null, resultMsg));
                return new ExecutionOutcome(jobResult, null);
            }

            this.lastStatus = "SUCCESS";
            String resultMsg = extractResultMessage(result);
            this.lastResultMsg = resultMsg;
            saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "SUCCESS",
                    resultMsg, null, null, retryCount.get(), context.getLogs());
            publishEvent(new JobSuccessEvent(this, jobName, triggerType, traceId, duration, result));
            JobResult finalResult = (result instanceof JobResult jobResult) ? jobResult : JobResult.success(resultMsg, result);
            return new ExecutionOutcome(finalResult, null);
        } catch (JobExecutionFailureException ex) {
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            this.lastDurationMs = duration;
            this.lastStatus = "FAILED";
            JobResult jobResult = ex.getResult();
            String resultMsg = extractResultMessage(jobResult);
            this.lastResultMsg = resultMsg;
            saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "FAILED",
                    resultMsg, jobResult.getMessage(), null, retryCount.get(), context.getLogs());
            publishEvent(new JobFailedEvent(this, jobName, triggerType, traceId, duration, retryCount.get(), null, resultMsg));
            return new ExecutionOutcome(jobResult, null);
        } catch (JobTimeoutException ex) {
            // 始终先记录 TIMEOUT 状态并广播 JobTimeoutEvent，保障指标与防风暴告警不漏报
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            this.lastDurationMs = duration;
            this.lastStatus = "TIMEOUT";
            this.lastResultMsg = ex.getMessage();
            publishEvent(new JobTimeoutEvent(this, jobName, triggerType, traceId, duration, ex.getTimeout(), retryCount.get()));

            try {
                JobResult fallbackResult = resolveTimeoutFallback(context, ex);
                if (fallbackResult != null) {
                    String fallbackSummary = extractResultMessage(fallbackResult);
                    context.log("Timeout fallback executed: " + fallbackSummary);
                    this.lastResultMsg = fallbackSummary;
                    saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "TIMEOUT",
                            fallbackSummary, ex.getMessage(), getStackTraceString(ex), retryCount.get(), context.getLogs());
                    return new ExecutionOutcome(fallbackResult, null);
                }
            } catch (Throwable fallbackEx) {
                // 降级补偿自身失败时仍必须保留 TIMEOUT 审计记录，且不覆盖 TIMEOUT 状态，随后保留原因果链向外传播
                ex.addSuppressed(fallbackEx);
                context.log("Timeout fallback execution failed: " + fallbackEx.getMessage());
                this.lastResultMsg = ex.getMessage() + " (fallback failed: " + fallbackEx.getMessage() + ")";
                saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "TIMEOUT",
                        this.lastResultMsg, this.lastResultMsg, getStackTraceString(fallbackEx),
                        retryCount.get(), context.getLogs());
                throw fallbackEx;
            }

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
            publishEvent(new JobFailedEvent(this, jobName, triggerType, traceId, duration, retryCount.get(), ex, ex.getMessage()));
            return new ExecutionOutcome(JobResult.failure(ex.getMessage()), ex);
        }
    }

    private void triggerAfterCompletion(List<LocalJobInterceptor> activeInterceptors, int passedIndex,
                                        JobContext context, JobResult result, Throwable ex) {
        for (int i = passedIndex; i >= 0; i--) {
            LocalJobInterceptor interceptor = activeInterceptors.get(i);
            try {
                interceptor.afterCompletion(context, result, ex);
            } catch (Throwable cleanupEx) {
                log.warn("LocalJobInterceptor [{}] afterCompletion threw exception for job [{}]: {}",
                        interceptor.getClass().getSimpleName(), definition.getJobName(), cleanupEx.getMessage(), cleanupEx);
            }
        }
    }

    private JobResult resolveTimeoutFallback(JobContext context, JobTimeoutException timeoutEx) {
        String declaredMethodName = definition.getFallbackMethod();
        boolean hasDeclaredFallback = declaredMethodName != null && !declaredMethodName.isBlank();
        if (hasDeclaredFallback) {
            Object rawTarget = (handler instanceof MethodInvokingJobHandler mijh) ? mijh.getTargetBean() : handler;
            if (rawTarget != null) {
                Class<?> targetClass = AopUtils.getTargetClass(rawTarget);
                Method fallbackMethod = findFallbackMethod(targetClass, declaredMethodName.trim(), timeoutEx);
                if (fallbackMethod != null) {
                    return invokeDeclaredFallbackMethod(rawTarget, fallbackMethod, declaredMethodName.trim(), context, timeoutEx);
                }
                log.warn("Job [{}] declared fallbackMethod [{}], but no compatible method found on [{}]",
                        definition.getJobName(), declaredMethodName, targetClass.getName());
            }
        }
        LocalJobTimeoutFallbackHandler fallbackHandler = timeoutFallbackHandlerProvider != null
                ? timeoutFallbackHandlerProvider.get()
                : null;
        if (fallbackHandler != null) {
            JobResult globalResult = fallbackHandler.onTimeout(context, timeoutEx);
            return normalizeFallbackResult(globalResult,
                    "Timeout fallback handled by [" + fallbackHandler.getClass().getSimpleName() + "]");
        }
        return null;
    }

    private JobResult invokeDeclaredFallbackMethod(Object rawTarget, Method method, String methodName,
                                                     JobContext context, JobTimeoutException timeoutEx) {
        Object invokeTarget = unwrapTargetBean(rawTarget);
        ReflectionUtils.makeAccessible(method);
        Object[] args = resolveFallbackArgs(method, context, timeoutEx);
        try {
            Object result = args.length == 0 ? method.invoke(invokeTarget) : method.invoke(invokeTarget, args);
            log.info("Job [{}] executed fallbackMethod [{}] after hard timeout", definition.getJobName(), methodName);
            return normalizeFallbackResult(result, "Timeout fallback [" + methodName + "] completed");
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException() != null ? ex.getTargetException() : ex;
            throw new IllegalStateException("Fallback method [" + methodName + "] failed: " + cause.getMessage(), cause);
        } catch (Throwable ex) {
            throw new IllegalStateException("Failed to invoke fallback method [" + methodName + "]: " + ex.getMessage(), ex);
        }
    }

    private Method findFallbackMethod(Class<?> targetClass, String methodName, JobTimeoutException timeoutEx) {
        List<Method> candidates = new ArrayList<>();
        Class<?> current = targetClass;
        while (current != null && !Object.class.equals(current)) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    candidates.add(method);
                }
            }
            current = current.getSuperclass();
        }
        if (candidates.isEmpty()) {
            return null;
        }
        for (int priority = 1; priority <= 4; priority++) {
            Method bestMatch = null;
            for (Method candidate : candidates) {
                if (matchesFallbackPriority(candidate.getParameterTypes(), priority, timeoutEx)) {
                    if (bestMatch == null || isMoreSpecificThrowableSignature(candidate.getParameterTypes(), bestMatch.getParameterTypes())) {
                        bestMatch = candidate;
                    }
                }
            }
            if (bestMatch != null) {
                return bestMatch;
            }
        }
        return null;
    }

    private boolean isMoreSpecificThrowableSignature(Class<?>[] candidateTypes, Class<?>[] currentBestTypes) {
        Class<?> candidateThrowable = extractThrowableParamType(candidateTypes);
        Class<?> currentBestThrowable = extractThrowableParamType(currentBestTypes);
        if (candidateThrowable == null || currentBestThrowable == null) {
            return false;
        }
        return !candidateThrowable.equals(currentBestThrowable) && currentBestThrowable.isAssignableFrom(candidateThrowable);
    }

    private Class<?> extractThrowableParamType(Class<?>[] paramTypes) {
        for (Class<?> paramType : paramTypes) {
            if (Throwable.class.isAssignableFrom(paramType)) {
                return paramType;
            }
        }
        return null;
    }

    private boolean matchesFallbackPriority(Class<?>[] paramTypes, int priority, JobTimeoutException timeoutEx) {
        return switch (priority) {
            case 1 -> paramTypes.length == 2
                    && ((JobContext.class.isAssignableFrom(paramTypes[0]) && isCompatibleThrowable(paramTypes[1], timeoutEx))
                    || (JobContext.class.isAssignableFrom(paramTypes[1]) && isCompatibleThrowable(paramTypes[0], timeoutEx)));
            case 2 -> paramTypes.length == 1 && JobContext.class.isAssignableFrom(paramTypes[0]);
            case 3 -> paramTypes.length == 1 && isCompatibleThrowable(paramTypes[0], timeoutEx);
            case 4 -> paramTypes.length == 0;
            default -> false;
        };
    }

    private boolean isCompatibleThrowable(Class<?> paramType, JobTimeoutException timeoutEx) {
        return Throwable.class.isAssignableFrom(paramType) && paramType.isInstance(timeoutEx);
    }

    private Object[] resolveFallbackArgs(Method method, JobContext context, JobTimeoutException timeoutEx) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return new Object[0];
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (JobContext.class.isAssignableFrom(paramTypes[i])) {
                args[i] = context;
            } else if (Throwable.class.isAssignableFrom(paramTypes[i])) {
                args[i] = timeoutEx;
            }
        }
        return args;
    }

    private Object unwrapTargetBean(Object bean) {
        if (AopUtils.isAopProxy(bean)) {
            Object singleton = AopProxyUtils.getSingletonTarget(bean);
            if (singleton != null) {
                return singleton;
            }
            if (bean instanceof Advised advised) {
                try {
                    Object target = advised.getTargetSource().getTarget();
                    if (target != null) {
                        return target;
                    }
                } catch (Exception ignored) {
                    // 回退使用原始代理对象
                }
            }
        }
        return bean;
    }

    private JobResult normalizeFallbackResult(Object result, String defaultMessage) {
        if (result instanceof JobResult jobResult) {
            return jobResult;
        }
        if (result instanceof String str && !str.isBlank()) {
            return JobResult.success(str, str);
        }
        return JobResult.success(defaultMessage, result);
    }

    private JobResult doExecuteIterate(TriggerType triggerType, Map<String, Object> params, String traceId, Instant startTime) {
        String jobName = definition.getJobName();
        TenantProvider provider = this.tenantProvider != null ? this.tenantProvider : new DefaultTenantProvider();
        List<String> rawTenantIds = provider.getTenantIds();
        List<String> tenantIds = new ArrayList<>();
        if (rawTenantIds != null) {
            for (String tid : rawTenantIds) {
                if (tid != null && !tid.isBlank()) {
                    tenantIds.add(tid.trim());
                }
            }
        }
        if (tenantIds.isEmpty()) {
            log.warn("Job [{}] in ITERATE tenant mode found no active tenants from TenantProvider, skipping execution", jobName);
            String msg = "无有效租户，跳过全租户轮询调度";
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            this.lastDurationMs = duration;
            this.lastStatus = "SUCCESS";
            this.lastResultMsg = msg;
            saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, "SUCCESS",
                    msg, null, null, 0, Collections.emptyList());
            publishEvent(new JobSuccessEvent(this, jobName, triggerType, traceId, duration, msg));
            return JobResult.success(msg);
        }

        int total = tenantIds.size();
        int concurrency = resolveTenantConcurrency();
        TenantIterationStats stats = new TenantIterationStats();

        if (concurrency > 1 && (workerExecutor != null || taskScheduler != null)) {
            doExecuteIterateParallel(tenantIds, concurrency, triggerType, params, traceId, stats);
        } else {
            for (String tenantId : tenantIds) {
                executeForSingleTenant(tenantId, triggerType, params, traceId, true, stats);
            }
        }

        int successCount = stats.successCount.get();
        int failureCount = stats.failureCount.get();
        long duration = Duration.between(startTime, Instant.now()).toMillis();
        this.lastDurationMs = duration;
        boolean allSuccess = (failureCount == 0);
        String summary = String.format("完成 %d/%d 租户调度，成功 %d，失败 %d%s",
                total, total, successCount, failureCount,
                stats.errorMessages.isEmpty() ? "" : "，详情: " + String.join("; ", stats.errorMessages));

        this.lastStatus = allSuccess ? "SUCCESS" : (successCount > 0 ? "PARTIAL_SUCCESS" : "FAILED");
        this.lastResultMsg = summary;

        List<String> logs = new ArrayList<>(stats.aggregatedLogs);
        saveExecutionRecord(traceId, triggerType, startTime, Instant.now(), duration, this.lastStatus,
                summary, allSuccess ? null : summary, null, stats.totalRetryCount.get(), logs);

        if (allSuccess) {
            publishEvent(new JobSuccessEvent(this, jobName, triggerType, traceId, duration, summary));
            return JobResult.success(summary);
        } else {
            publishEvent(new JobFailedEvent(this, jobName, triggerType, traceId, duration, stats.totalRetryCount.get(), null, summary));
            return JobResult.failure(summary);
        }
    }

    private void doExecuteIterateParallel(List<String> tenantIds, int concurrency, TriggerType triggerType,
                                          Map<String, Object> params, String traceId, TenantIterationStats stats) {
        AsyncTaskExecutor executor = workerExecutor != null ? workerExecutor : taskScheduler;
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>(tenantIds.size());

        for (int i = 0; i < tenantIds.size(); i++) {
            String tenantId = tenantIds.get(i);
            try {
                semaphore.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                for (int j = i; j < tenantIds.size(); j++) {
                    String skippedTenant = tenantIds.get(j);
                    stats.failureCount.incrementAndGet();
                    stats.errorMessages.add("租户 [" + skippedTenant + "] 因并发调度被中断而未执行");
                }
                break;
            }

            // 外层已由当前并发分发投入受管 Worker 池执行；超时通过独立调度器延时中断当前 Worker 线程实现，彻底杜绝同池嵌套饥饿死锁
            Runnable rawTenantTask = () -> {
                try {
                    executeForSingleTenant(tenantId, triggerType, params, traceId, false, stats);
                } finally {
                    semaphore.release();
                }
            };
            Runnable wrappedTenantTask = AsyncContext.wrap(rawTenantTask);
            try {
                CompletableFuture<Void> future = CompletableFuture.runAsync(wrappedTenantTask, executor);
                futures.add(future);
            } catch (Throwable submitEx) {
                semaphore.release();
                stats.failureCount.incrementAndGet();
                stats.errorMessages.add("租户 [" + tenantId + "] 提交执行失败: " + submitEx.getMessage());
                log.error("Failed to submit concurrent tenant task for job [{}], tenant [{}]: {}",
                        definition.getJobName(), tenantId, submitEx.getMessage(), submitEx);
            }
        }

        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    private void executeForSingleTenant(String tenantId, TriggerType triggerType, Map<String, Object> params,
                                        String traceId, boolean allowNestedWorker, TenantIterationStats stats) {
        String jobName = definition.getJobName();
        TenantContextHolder.TenantSnapshot tenantSnapshot = TenantContextHolder.capturePrevious();
        try {
            TenantContextHolder.setTenantId(tenantId);
            JobContext tenantContext = buildExecutionContext(triggerType, params, traceId, Instant.now(), tenantId);
            tenantContext.log("Starting execution for tenant [" + tenantId + "]");
            AtomicInteger retryCount = new AtomicInteger(0);
            Object result = executeWithRetryAndTimeout(tenantContext, retryCount, allowNestedWorker);
            stats.totalRetryCount.addAndGet(retryCount.get());
            stats.aggregatedLogs.addAll(tenantContext.getLogs());

            if (result instanceof JobResult jr && !jr.isSuccess()) {
                stats.failureCount.incrementAndGet();
                stats.errorMessages.add("租户 [" + tenantId + "] 失败: " + jr.getMessage());
                log.warn("Job [{}] failed for tenant [{}]: {}", jobName, tenantId, jr.getMessage());
            } else {
                stats.successCount.incrementAndGet();
                log.debug("Job [{}] succeeded for tenant [{}]", jobName, tenantId);
            }
        } catch (Throwable t) {
            stats.failureCount.incrementAndGet();
            stats.errorMessages.add("租户 [" + tenantId + "] 异常: " + t.getMessage());
            log.error("Job [{}] threw exception for tenant [{}]: {}", jobName, tenantId, t.getMessage(), t);
        } finally {
            TenantContextHolder.restore(tenantSnapshot);
        }
    }

    private JobContext buildExecutionContext(TriggerType triggerType, Map<String, Object> params,
                                             String traceId, Instant fireTime, String tenantId) {
        return JobContext.builder()
                .jobName(definition.getJobName())
                .triggerType(triggerType)
                .fireTime(fireTime)
                .tenantId(tenantId)
                .params(params != null ? params : Collections.emptyMap())
                .traceId(traceId)
                .maxLogLines(resolveMaxLogLines())
                .build();
    }

    private String resolveInitialTenantId(Map<String, Object> params) {
        if (definition.getTenantMode() == JobTenantMode.ITERATE || definition.getTenantMode() == JobTenantMode.IGNORE) {
            return null;
        }
        if (definition.getTenantMode() == JobTenantMode.PARAM && params != null && params.containsKey("tenantId")
                && params.get("tenantId") != null) {
            return String.valueOf(params.get("tenantId"));
        }
        return TenantContextHolder.getTenantId();
    }

    private int resolveTenantConcurrency() {
        return definition.getTenantConcurrency() > 0 ? definition.getTenantConcurrency() : 1;
    }

    private record ExecutionOutcome(JobResult result, Throwable exception) {
    }

    private static final class TenantIterationStats {
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger totalRetryCount = new AtomicInteger(0);
        private final Queue<String> errorMessages = new ConcurrentLinkedQueue<>();
        private final Queue<String> aggregatedLogs = new ConcurrentLinkedQueue<>();
    }

    private Object executeWithRetryAndTimeout(JobContext context, AtomicInteger retryCount, boolean allowNestedWorker) {
        int maxRetries = definition.getMaxRetries();
        Duration retryInterval = definition.getRetryInterval() != null ? definition.getRetryInterval() : Duration.ofSeconds(1);
        String jobName = definition.getJobName();

        Supplier<Object> supplier = () -> {
            try {
                Object res = executeSingleAttempt(context, allowNestedWorker);
                if (res instanceof JobResult jr && !jr.isSuccess() && maxRetries > 0) {
                    throw new JobExecutionFailureException(jr);
                }
                return res;
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
                    .retryPredicate(error -> (error instanceof JobTimeoutException) || !(error instanceof BusinessException))
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

    private Object executeSingleAttempt(JobContext context, boolean allowNestedWorker) throws Throwable {
        Duration timeout = definition.getTimeout();
        boolean hasTimeout = timeout != null && !timeout.isZero() && !timeout.isNegative();
        // 调度池与执行池物理隔离：允许切入 Worker 且配置了 workerExecutor 或超时控制时委托至 Worker 池
        if (allowNestedWorker && (workerExecutor != null || (taskScheduler != null && hasTimeout))) {
            return executeInWorker(context, timeout);
        }
        // 已运行在受管并发 Worker 线程且要求超时控制时：通过调度器延时中断看门狗或时间截止校验实现无同池嵌套阻塞的硬超时
        if (hasTimeout) {
            return executeDirectlyWithWatchdog(context, timeout);
        }
        return executeDirectly(context);
    }

    private Object executeDirectlyWithWatchdog(JobContext context, Duration timeout) throws Throwable {
        String jobName = definition.getJobName();
        Thread workerThread = Thread.currentThread();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Instant start = Instant.now();
        ScheduledFuture<?> timeoutTrigger = null;
        if (taskScheduler != null) {
            timeoutTrigger = taskScheduler.schedule(() -> {
                if (timedOut.compareAndSet(false, true)) {
                    workerThread.interrupt();
                }
            }, start.plus(timeout));
        }

        executingThread.set(workerThread);
        try {
            Object result = handler.execute(context);
            if (timedOut.get() || Duration.between(start, Instant.now()).compareTo(timeout) > 0) {
                throw new JobTimeoutException("Job [" + jobName + "] execution timed out after " + timeout, jobName, timeout, null);
            }
            return result;
        } catch (JobTimeoutException ex) {
            throw ex;
        } catch (Throwable ex) {
            if (timedOut.get() || (ex instanceof InterruptedException && Duration.between(start, Instant.now()).compareTo(timeout) >= 0)) {
                throw new JobTimeoutException("Job [" + jobName + "] execution timed out after " + timeout, jobName, timeout, ex);
            }
            throw ex;
        } finally {
            if (timeoutTrigger != null) {
                timeoutTrigger.cancel(false);
            }
            executingThread.compareAndSet(workerThread, null);
            if (timedOut.get()) {
                // 清除仅由看门狗超时中断置位的中断状态，避免污染池化 Worker 线程后续执行或上下文清理
                Thread.interrupted();
            }
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

    private Object executeInWorker(JobContext context, Duration timeout) throws Throwable {
        String jobName = definition.getJobName();
        AtomicReference<Thread> workerThreadRef = new AtomicReference<>();

        Callable<Object> rawTask = () -> {
            Thread worker = Thread.currentThread();
            workerThreadRef.set(worker);
            executingThread.set(worker);
            try {
                return handler.execute(context);
            } finally {
                executingThread.compareAndSet(worker, null);
            }
        };

        // 依托 Core 模块 AsyncContext.wrap 实现四维/五维快照全链路透传并在执行后对称恢复现场
        Callable<Object> workerTask = AsyncContext.wrap(rawTask);
        Future<Object> future = submitTask(workerTask);

        try {
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                return future.get();
            }
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
            Thread worker = workerThreadRef.get();
            if (worker != null && worker.isAlive()) {
                worker.interrupt();
            }
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
        // 兜底降级：严禁私自 new Thread 创建野线程，同步执行并返回已完成的 CompletableFuture
        try {
            return CompletableFuture.completedFuture(task.call());
        } catch (Throwable ex) {
            CompletableFuture<Object> cf = new CompletableFuture<>();
            cf.completeExceptionally(ex);
            return cf;
        }
    }

    /**
     * 用于在自适应重试期间触发 JobResult.failure 退避重试的受控标记异常
     */
    static class JobExecutionFailureException extends RuntimeException {
        private final JobResult result;

        JobExecutionFailureException(JobResult result) {
            super(result != null ? result.getMessage() : "Job execution failed");
            this.result = result;
        }

        public JobResult getResult() {
            return result;
        }
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
                .clusterLockMinHoldTime(definition.getClusterLockMinHoldTime())
                .fallbackMethod(definition.getFallbackMethod())
                .tenantConcurrency(resolveTenantConcurrency())
                .description(definition.getDescription())
                .tenantMode(definition.getTenantMode())
                .nextExecutionTime(resolveNextExecutionTime())
                .lastExecutionTime(this.lastExecutionTime)
                .lastDurationMs(this.lastDurationMs)
                .lastStatus(this.lastStatus)
                .lastResultMsg(this.lastResultMsg)
                .lastTraceId(this.lastTraceId)
                .executing(isExecuting())
                .build();
    }

    private Instant resolveNextExecutionTime() {
        if (this.status != JobStatus.RUNNING || TriggerType.MANUAL.equals(definition.getTriggerType())) {
            return null;
        }
        Instant now = Instant.now();
        if (TriggerType.CRON.equals(definition.getTriggerType())
                || (definition.getCron() != null && !definition.getCron().isBlank())) {
            try {
                if (definition.getCron() != null && !definition.getCron().isBlank()) {
                    CronExpression cronExpression = CronExpression.parse(definition.getCron().trim());
                    ZonedDateTime next = cronExpression.next(ZonedDateTime.ofInstant(now, ZoneId.systemDefault()));
                    return next != null ? next.toInstant() : null;
                }
            } catch (Exception ex) {
                log.debug("Failed to calculate next execution time from cron [{}]: {}",
                        definition.getCron(), ex.getMessage());
            }
        }
        ScheduledFuture<?> future = this.scheduledFuture;
        if (future != null && !future.isCancelled() && !future.isDone()) {
            long delayMs = future.getDelay(TimeUnit.MILLISECONDS);
            if (delayMs > 0) {
                return now.plusMillis(delayMs);
            }
        }
        Duration interval = TriggerType.FIXED_RATE.equals(definition.getTriggerType())
                ? definition.getFixedRate()
                : definition.getFixedDelay();
        if (interval == null) {
            interval = definition.getFixedRate() != null ? definition.getFixedRate() : definition.getFixedDelay();
        }
        if (interval != null && !interval.isZero() && !interval.isNegative()) {
            Instant base = this.lastExecutionTime;
            if (base != null) {
                Instant candidate = base.plus(interval);
                if (candidate.isAfter(now)) {
                    return candidate;
                }
            } else if (definition.getInitialDelay() != null
                    && !definition.getInitialDelay().isZero()
                    && !definition.getInitialDelay().isNegative()) {
                return now.plus(definition.getInitialDelay());
            }
            return now.plus(interval);
        }
        return null;
    }

    /**
     * 根据任务定义构建对应的 Trigger
     */
    public static Trigger buildTrigger(JobDefinition definition) {
        if (TriggerType.MANUAL.equals(definition.getTriggerType())) {
            return null;
        }
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
        return null;
    }

    private void publishEvent(ApplicationEvent event) {
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(event);
            } catch (Throwable t) {
                log.warn("Failed to publish job event [{}]: {}", event.getClass().getSimpleName(), t.getMessage());
            }
        }
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
            Duration logRetention = resolveLogRetention();
            if (logRetention != null && !logRetention.isZero() && !logRetention.isNegative()) {
                logStorageProvider.cleanExpiredLogs(logRetention);
            }
        } catch (Throwable t) {
            log.warn("Failed to persist execution log for job [{}]: {}", definition.getJobName(), t.getMessage());
        }
    }

    private int resolveMaxLogLines() {
        if (properties != null && properties.getMaxLogLinesPerExecution() > 0) {
            return properties.getMaxLogLinesPerExecution();
        }
        return JobContext.DEFAULT_MAX_LOG_LINES;
    }

    private Duration resolveLogRetention() {
        if (properties != null) {
            return properties.getLogRetention();
        }
        return Duration.ofDays(7);
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
