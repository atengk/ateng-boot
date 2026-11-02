package io.github.atengk.boot.job.snail.aspect;

import com.aizuda.snailjob.client.job.core.MapHandler;
import com.aizuda.snailjob.common.core.enums.StatusEnum;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MapExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MergeReduceExecutor;
import com.aizuda.snailjob.client.job.core.annotation.ReduceExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.MapArgs;
import com.aizuda.snailjob.client.job.core.dto.MergeReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ShardingJobArgs;
import com.aizuda.snailjob.client.job.core.executor.JobContextManager;
import com.aizuda.snailjob.common.core.model.JobContext;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.job.snail.alert.SnailJobAlertAggregator;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.interceptor.SnailJobExecutionContext;
import io.github.atengk.boot.job.snail.interceptor.SnailJobInterceptor;
import io.github.atengk.boot.job.snail.metrics.SnailJobMetricsBinder;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.watchdog.JobTimeoutFallbackHandler;
import io.github.atengk.boot.job.snail.watchdog.SnailJobExecutionMetadata;
import io.github.atengk.boot.job.snail.watchdog.SnailLocalWatchdog;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SnailJob 调度任务上下文、链路追踪、形参注入与受管超时看门狗切面
 *
 * <p>无感拦截由 {@link JobExecutor}、{@link AtengSnailJob} 声明的调度任务执行方法，在任务执行前自动织入：
 * <ul>
 *     <li><b>MDC 链路追踪</b>：提取任务入参中的 traceId 或基于无锁算法生成 32 位唯一标识，写入 MDC；</li>
 *     <li><b>多租户隔离策略</b>：解析 {@link JobTenantMode}，按需标记 {@code IGNORE} 忽略租户过滤或从入参中提取 {@code tenantId}；</li>
 *     <li><b>操作人上下文</b>：自动绑定调度中心或系统身份标识（默认为 {@code SYSTEM}）；</li>
 *     <li><b>方法形参智能绑定</b>：支持自动注入 {@link JobArgs} 原生参数、反序列化强类型 DTO 及原生字符串实参；</li>
 *     <li><b>JSR-303 参数校验</b>：针对挂载 {@code @Valid}、{@code @Validated} 或约束注解的方法形参实施 Fail-Fast 防卫；</li>
 *     <li><b>本地硬超时看门狗</b>：解析方法级 {@link AtengSnailJob#timeout()}，超时时强中断工作线程并广播事件。</li>
 * </ul>
 * 并在任务完成后无论成功或异常，严格执行现场恢复与现场清理，彻底杜绝线程池复用污染与上下文泄露。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class SnailJobContextAspect {

    private static final Logger log = LoggerFactory.getLogger(SnailJobContextAspect.class);

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TASK_NAME_KEY = "snail.taskName";
    public static final String DEFAULT_JOB_USER = "SYSTEM";

    private final SnailLocalWatchdog watchdog;
    private final SnailJobProperties properties;
    private final GracefulDrainStateMachine drainStateMachine;
    private final SnailJobMetricsBinder metricsBinder;
    private final SnailJobAlertAggregator alertAggregator;
    private final java.util.List<SnailJobInterceptor> interceptors;
    private final java.util.List<JobTimeoutFallbackHandler> fallbackHandlers;
    private final SnailJobRuntimeGovernor governor;

    public SnailJobContextAspect() {
        this(null, null, null, null, null, null, null, null);
    }

    public SnailJobContextAspect(SnailLocalWatchdog watchdog, SnailJobProperties properties) {
        this(watchdog, properties, null, null, null, null, null, null);
    }

    public SnailJobContextAspect(
            SnailLocalWatchdog watchdog,
            SnailJobProperties properties,
            GracefulDrainStateMachine drainStateMachine,
            SnailJobMetricsBinder metricsBinder,
            SnailJobAlertAggregator alertAggregator) {
        this(watchdog, properties, drainStateMachine, metricsBinder, alertAggregator, null, null, null);
    }

    public SnailJobContextAspect(
            SnailLocalWatchdog watchdog,
            SnailJobProperties properties,
            GracefulDrainStateMachine drainStateMachine,
            SnailJobMetricsBinder metricsBinder,
            SnailJobAlertAggregator alertAggregator,
            java.util.List<SnailJobInterceptor> interceptors) {
        this(watchdog, properties, drainStateMachine, metricsBinder, alertAggregator, interceptors, null, null);
    }

    public SnailJobContextAspect(
            SnailLocalWatchdog watchdog,
            SnailJobProperties properties,
            GracefulDrainStateMachine drainStateMachine,
            SnailJobMetricsBinder metricsBinder,
            SnailJobAlertAggregator alertAggregator,
            java.util.List<SnailJobInterceptor> interceptors,
            java.util.List<JobTimeoutFallbackHandler> fallbackHandlers) {
        this(watchdog, properties, drainStateMachine, metricsBinder, alertAggregator, interceptors, fallbackHandlers, null);
    }

    public SnailJobContextAspect(
            SnailLocalWatchdog watchdog,
            SnailJobProperties properties,
            GracefulDrainStateMachine drainStateMachine,
            SnailJobMetricsBinder metricsBinder,
            SnailJobAlertAggregator alertAggregator,
            java.util.List<SnailJobInterceptor> interceptors,
            java.util.List<JobTimeoutFallbackHandler> fallbackHandlers,
            SnailJobRuntimeGovernor governor) {
        this.watchdog = watchdog;
        this.properties = properties;
        this.drainStateMachine = drainStateMachine;
        this.metricsBinder = metricsBinder;
        this.alertAggregator = alertAggregator;
        if (interceptors != null && !interceptors.isEmpty()) {
            java.util.List<SnailJobInterceptor> sorted = new java.util.ArrayList<>(interceptors);
            AnnotationAwareOrderComparator.sort(sorted);
            this.interceptors = java.util.Collections.unmodifiableList(sorted);
        } else {
            this.interceptors = java.util.Collections.emptyList();
        }
        if (fallbackHandlers != null && !fallbackHandlers.isEmpty()) {
            java.util.List<JobTimeoutFallbackHandler> sorted = new java.util.ArrayList<>(fallbackHandlers);
            AnnotationAwareOrderComparator.sort(sorted);
            this.fallbackHandlers = java.util.Collections.unmodifiableList(sorted);
        } else {
            this.fallbackHandlers = java.util.Collections.emptyList();
        }
        this.governor = governor;
    }

    public java.util.List<SnailJobInterceptor> getInterceptors() {
        return interceptors;
    }

    public java.util.List<JobTimeoutFallbackHandler> getFallbackHandlers() {
        return fallbackHandlers;
    }

    public SnailJobRuntimeGovernor getGovernor() {
        return governor;
    }

    @Around("@annotation(com.aizuda.snailjob.client.job.core.annotation.JobExecutor) || " +
            "@annotation(com.aizuda.snailjob.client.job.core.annotation.MapExecutor) || " +
            "@annotation(com.aizuda.snailjob.client.job.core.annotation.ReduceExecutor) || " +
            "@annotation(com.aizuda.snailjob.client.job.core.annotation.MergeReduceExecutor) || " +
            "@annotation(io.github.atengk.boot.job.snail.annotation.AtengSnailJob) || " +
            "@within(com.aizuda.snailjob.client.job.core.annotation.JobExecutor) || " +
            "@within(com.aizuda.snailjob.client.job.core.annotation.MapExecutor) || " +
            "@within(com.aizuda.snailjob.client.job.core.annotation.ReduceExecutor) || " +
            "@within(com.aizuda.snailjob.client.job.core.annotation.MergeReduceExecutor) || " +
            "@within(io.github.atengk.boot.job.snail.annotation.AtengSnailJob) || " +
            "execution(* com.aizuda.snailjob.client.job.core.IJobExecutor+.jobExecute(..)) || " +
            "execution(* com.aizuda.snailjob.client.job.core.executor.AbstractMapExecutor+.doJobMapExecute(..)) || " +
            "execution(* com.aizuda.snailjob.client.job.core.executor.AbstractMapReduceExecutor+.doReduceExecute(..)) || " +
            "execution(* com.aizuda.snailjob.client.job.core.executor.AbstractMapReduceExecutor+.doMergeReduceExecute(..))")
    public Object aroundJob(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : method.getDeclaringClass();
        Method specificMethod = org.springframework.aop.support.AopUtils.getMostSpecificMethod(method, targetClass);

        AtengSnailJob atengSnailJob = AnnotatedElementUtils.findMergedAnnotation(specificMethod, AtengSnailJob.class);
        if (atengSnailJob == null) {
            atengSnailJob = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengSnailJob.class);
        }
        JobTenantMode tenantMode = (atengSnailJob != null) ? atengSnailJob.tenantMode() : JobTenantMode.IGNORE;

        // 0. 平滑停机排空拦截与在途计数
        if (drainStateMachine != null && drainStateMachine.isDraining()) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE, "SnailJob 执行器正处于平滑停机排空阶段，拒绝接收新调度任务");
        }
        if (drainStateMachine != null) {
            drainStateMachine.incrementInFlightJob();
        }
        long startTimeNanos = System.nanoTime();

        // 1. 尝试获取任务原始入参 (优先从 JobArgs/JobContext，回退至方法实参)
        JobArgs jobArgs = null;
        String rawParam = null;
        if (pjp.getArgs() != null) {
            for (Object arg : pjp.getArgs()) {
                if (arg instanceof JobArgs ja) {
                    jobArgs = ja;
                    if (ja.getJobParams() instanceof String s) {
                        rawParam = s;
                    } else if (ja.getJobParams() != null) {
                        rawParam = JsonUtil.toJsonString(ja.getJobParams());
                    }
                    break;
                } else if (arg instanceof JobContext jc) {
                    if (jc.getJobArgsHolder() != null && jc.getJobArgsHolder().getJobParams() != null) {
                        Object p = jc.getJobArgsHolder().getJobParams();
                        rawParam = (p instanceof String s) ? s : JsonUtil.toJsonString(p);
                    }
                } else if (arg instanceof String s) {
                    if (rawParam == null) {
                        rawParam = s;
                    }
                }
            }
        }

        // 2. MDC traceId 与 taskName 链路追踪上下文编排
        String previousTraceId = MDC.get(TRACE_ID_KEY);
        String previousTaskName = MDC.get(TASK_NAME_KEY);
        String extractedTraceId = extractTraceId(rawParam, pjp.getArgs());
        String traceId = StringUtils.hasText(extractedTraceId) ? extractedTraceId : (StringUtils.hasText(previousTraceId) ? previousTraceId : IdUtil.fastSimpleUUID());
        MDC.put(TRACE_ID_KEY, traceId);

        // 3. UserContextHolder 操作人上下文编排
        String previousUser = UserContextHolder.getExplicit();
        String extractedUser = extractUserId(rawParam, pjp.getArgs());
        UserContextHolder.set(StringUtils.hasText(extractedUser) ? extractedUser : (StringUtils.hasText(previousUser) ? previousUser : DEFAULT_JOB_USER));

        // 4. TenantContextHolder 多租户上下文编排
        TenantSnapshot previousTenantSnapshot = TenantContextHolder.capturePrevious();
        if (tenantMode == JobTenantMode.IGNORE) {
            TenantContextHolder.setIgnoreTenant(true);
        } else if (tenantMode == JobTenantMode.PARAM) {
            String tenantId = extractTenantId(rawParam, pjp.getArgs());
            if (StringUtils.hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            }
        } else if (tenantMode == JobTenantMode.ITERATE) {
            TenantContextHolder.clear();
            log.debug("SnailJob 任务处于多租户遍历模式 (ITERATE)，已清空外部租户，交由 TenantJobTemplate 执行");
        }

        // 5. 本地硬看门狗与执行元数据解析
        String jobName = resolveJobName(specificMethod, targetClass, atengSnailJob, jobArgs);
        Long jobId = (jobArgs != null && jobArgs.getJobId() != null) ? jobArgs.getJobId() : 0L;
        Long taskBatchId = (jobArgs != null && jobArgs.getTaskBatchId() != null) ? jobArgs.getTaskBatchId() : 0L;
        Duration timeout = resolveTimeout(atengSnailJob);

        SnailJobExecutionMetadata metadata = SnailJobExecutionMetadata.builder()
                .jobId(jobId)
                .jobName(jobName)
                .taskBatchId(taskBatchId)
                .traceId(MDC.get(TRACE_ID_KEY))
                .tenantId(TenantContextHolder.getTenantId())
                .build();

        AtomicReference<SnailLocalWatchdog.WatchdogSession> sessionRef =
                new AtomicReference<>(SnailLocalWatchdog.WatchdogSession.NOOP);

        // 构建 SnailJobContext 并绑定当前线程
        SnailJobContext snailJobContext = new SnailJobContext();
        snailJobContext.setJobId(jobId);
        snailJobContext.setJobName(jobName);
        snailJobContext.setTaskBatchId(taskBatchId);
        snailJobContext.setJobArgs(jobArgs);
        snailJobContext.setRawJobParams(rawParam);

        JobContext nativeJobContext = null;
        try {
            nativeJobContext = JobContextManager.getJobContext();
        } catch (Throwable ignored) {
        }
        snailJobContext.setNativeJobContext(nativeJobContext);

        if (jobArgs instanceof ShardingJobArgs sja) {
            snailJobContext.setShardingTotal(sja.getShardingTotal());
            snailJobContext.setShardingIndex(sja.getShardingIndex());
        } else if (nativeJobContext != null && nativeJobContext.getShardingTotal() != null) {
            snailJobContext.setShardingTotal(nativeJobContext.getShardingTotal());
            snailJobContext.setShardingIndex(nativeJobContext.getShardingIndex());
        }

        if (jobArgs instanceof MapArgs ma && StringUtils.hasText(ma.getTaskName())) {
            snailJobContext.setTaskName(ma.getTaskName());
        } else if (jobName.contains(":") && !StringUtils.hasText(snailJobContext.getTaskName())) {
            snailJobContext.setTaskName(jobName.substring(jobName.indexOf(":") + 1));
        }

        if (StringUtils.hasText(snailJobContext.getTaskName())) {
            MDC.put(TASK_NAME_KEY, snailJobContext.getTaskName());
        }

        if (jobArgs != null) {
            snailJobContext.setWorkflowTaskBatchId(jobArgs.getWorkflowTaskBatchId());
            snailJobContext.setWorkflowNodeId(jobArgs.getWorkflowNodeId());
            snailJobContext.setJobBizId(jobArgs.getJobBizId());
            if (jobArgs.getWfContext() != null) {
                snailJobContext.appendWfContext(jobArgs.getWfContext());
            }
            if (jobArgs.getChangeWfContext() != null) {
                snailJobContext.appendWfContext(jobArgs.getChangeWfContext());
            }
        } else if (nativeJobContext != null) {
            snailJobContext.setWorkflowTaskBatchId(nativeJobContext.getWorkflowTaskBatchId());
            snailJobContext.setWorkflowNodeId(nativeJobContext.getWorkflowNodeId());
            snailJobContext.setJobBizId(nativeJobContext.getJobBizId());
            snailJobContext.setTaskId(nativeJobContext.getTaskId());
            snailJobContext.setTaskName(nativeJobContext.getTaskName());
            snailJobContext.setMrStage(nativeJobContext.getMrStage());
            if (nativeJobContext.getWfContext() != null) {
                snailJobContext.appendWfContext(nativeJobContext.getWfContext());
            }
            if (nativeJobContext.getChangeWfContext() != null) {
                snailJobContext.appendWfContext(nativeJobContext.getChangeWfContext());
            }
        }
        SnailJobContextHolder.set(snailJobContext);

        java.util.List<SnailJobInterceptor> executedInterceptors = new java.util.ArrayList<>(this.interceptors.size());
        SnailJobExecutionContext executionContext = null;
        Object[] resolvedArgs = null;
        Object executionResult = null;
        Throwable errorThrown = null;

        try {
            // 0. 节点级运行时动态治理状态检查 (若已被暂停，直接跳过并向服务端反馈成功)
            if (governor != null && governor.isPaused(jobName)) {
                log.info("[SKIPPED] 任务调度已被节点级运行时治理总线暂停: jobName={}, traceId={}", jobName, traceId);
                Object skipResult = createGovernorSkippedResult(specificMethod, jobName);
                executionContext = new SnailJobExecutionContext(
                        pjp.getTarget(),
                        specificMethod,
                        pjp.getArgs(),
                        metadata
                );
                executionResult = skipResult;
                if (metricsBinder != null) {
                    metricsBinder.recordJobExecution(jobName, SnailJobMetricsBinder.STATUS_SKIPPED, TenantContextHolder.getTenantId(), Duration.ZERO);
                }
                return skipResult;
            }

            // A. 解析方法形参并完成强类型绑定 (JobArgs、JobContext、DTO、String)
            resolvedArgs = resolveArguments(specificMethod, pjp.getArgs(), rawParam, jobArgs, snailJobContext);

            // B. 若租户模式为 PARAM 且前面未提取到租户，从反序列化后的实参中二次探测
            if (tenantMode == JobTenantMode.PARAM && !StringUtils.hasText(TenantContextHolder.getTenantId())) {
                String tenantIdFromResolved = extractTenantIdFromArgs(resolvedArgs);
                if (StringUtils.hasText(tenantIdFromResolved)) {
                    TenantContextHolder.setTenantId(tenantIdFromResolved);
                }
            }

            // 构建拦截器上下文
            executionContext = new SnailJobExecutionContext(
                    pjp.getTarget(),
                    specificMethod,
                    resolvedArgs,
                    metadata
            );

            // C. 拦截器 preHandle 前置拦截执行 (在参数校验之前执行，支持拦截器短路跳过与灰度绕过)
            if (!this.interceptors.isEmpty()) {
                for (SnailJobInterceptor interceptor : this.interceptors) {
                    boolean proceed = interceptor.preHandle(executionContext);
                    if (!proceed) {
                        log.info("[INTERCEPTED] 任务调度被拦截器阻断并跳过执行: interceptor={}, jobName={}, traceId={}",
                                interceptor.getClass().getSimpleName(), jobName, traceId);
                        Object skipResult = createInterceptedSuccessResult(specificMethod, interceptor);
                        executionResult = skipResult;
                        return skipResult;
                    }
                    executedInterceptors.add(interceptor);
                }
            }

            // D. JSR-303 参数校验与 Fail-Fast 快速失败防卫 (仅对未被拦截短路放行的任务执行)
            validateParameters(pjp.getTarget(), specificMethod, specificMethod.getParameters(), resolvedArgs, jobName);

            // E. 挂载看门狗
            if (watchdog != null && timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                SnailLocalWatchdog.WatchdogSession session = watchdog.startWatch(Thread.currentThread(), timeout, metadata);
                sessionRef.set(session);
            }

            // F. 执行调度逻辑
            executionResult = (resolvedArgs != null && resolvedArgs.length > 0) ? pjp.proceed(resolvedArgs) : pjp.proceed();

            // 拦截器 postHandle 后置处理 (逆序执行)
            if (!executedInterceptors.isEmpty()) {
                for (int i = executedInterceptors.size() - 1; i >= 0; i--) {
                    try {
                        executedInterceptors.get(i).postHandle(executionContext, executionResult);
                    } catch (Throwable it) {
                        log.error("[SnailJob] 拦截器 [{}] postHandle 执行异常: {}",
                                executedInterceptors.get(i).getClass().getSimpleName(), it.getMessage(), it);
                    }
                }
            }

            long durationMs = Duration.ofNanos(System.nanoTime() - startTimeNanos).toMillis();
            if (metricsBinder != null) {
                metricsBinder.recordJobExecution(jobName, SnailJobMetricsBinder.STATUS_SUCCESS, TenantContextHolder.getTenantId(), Duration.ofMillis(durationMs));
            }
            if (alertAggregator != null) {
                alertAggregator.onJobSuccess(jobName, rawParam, traceId, durationMs);
            }
            return executionResult;

        } catch (Throwable t) {
            SnailLocalWatchdog.WatchdogSession session = sessionRef.get();
            boolean isTimedOut = (session != null) && (session.isTimedOut() || (t instanceof InterruptedException && session.isTimedOut()));
            if (isTimedOut) {
                log.warn("SnailJob 任务 [{}] 本地硬超时 (阈值: {}) 并已被中断", jobName, timeout);
                long elapsedNanos = System.nanoTime() - startTimeNanos;
                Duration elapsed = Duration.ofNanos(elapsedNanos);

                // 尝试执行方法级就地降级补偿
                String fallbackMethodName = (atengSnailJob != null) ? atengSnailJob.fallbackMethod().trim() : "";
                if (StringUtils.hasText(fallbackMethodName)) {
                    try {
                        Object fallbackResult = invokeFallbackMethod(
                                pjp.getTarget(),
                                targetClass,
                                fallbackMethodName,
                                metadata,
                                elapsed,
                                t,
                                resolvedArgs,
                                pjp.getArgs()
                        );
                        log.info("[SnailJob] 任务 [{}] 超时就地降级补偿方法 [{}] 执行成功", jobName, fallbackMethodName);
                        Object finalResult = (fallbackResult != null)
                                ? fallbackResult
                                : createFallbackSuccessResult(specificMethod, fallbackMethodName);
                        executionResult = finalResult;
                        errorThrown = null;

                        // 拦截器 postHandle 后置处理 (逆序执行)
                        if (!executedInterceptors.isEmpty()) {
                            SnailJobExecutionContext ctx = (executionContext != null)
                                    ? executionContext
                                    : new SnailJobExecutionContext(pjp.getTarget(), specificMethod, (resolvedArgs != null ? resolvedArgs : pjp.getArgs()), metadata);
                            for (int i = executedInterceptors.size() - 1; i >= 0; i--) {
                                try {
                                    executedInterceptors.get(i).postHandle(ctx, executionResult);
                                } catch (Throwable it) {
                                    log.error("[SnailJob] 拦截器 [{}] postHandle 执行异常: {}",
                                            executedInterceptors.get(i).getClass().getSimpleName(), it.getMessage(), it);
                                }
                            }
                        }

                        long durationMs = elapsed.toMillis();
                        boolean fallbackSuccess = isResultSuccess(finalResult);
                        if (metricsBinder != null) {
                            String metricStatus = fallbackSuccess ? SnailJobMetricsBinder.STATUS_SUCCESS : SnailJobMetricsBinder.STATUS_FAILURE;
                            metricsBinder.recordJobExecution(jobName, metricStatus, TenantContextHolder.getTenantId(), elapsed);
                        }
                        if (alertAggregator != null) {
                            if (fallbackSuccess) {
                                alertAggregator.onJobSuccess(jobName, rawParam, traceId, durationMs);
                            } else {
                                alertAggregator.onJobFailure(jobName, rawParam, traceId, durationMs, t);
                            }
                        }
                        return finalResult;
                    } catch (Throwable fallbackEx) {
                        log.error("[SnailJob] 任务 [{}] 就地降级补偿方法 [{}] 执行异常: {}",
                                jobName, fallbackMethodName, fallbackEx.getMessage(), fallbackEx);
                    }
                }

                // 若未配置就地降级或就地降级执行抛出异常，触发全局超时降级 SPI
                triggerGlobalFallbackHandlers(metadata, elapsed);
            }

            errorThrown = t;
            long durationMs = Duration.ofNanos(System.nanoTime() - startTimeNanos).toMillis();
            if (metricsBinder != null) {
                metricsBinder.recordJobExecution(jobName, SnailJobMetricsBinder.STATUS_FAILURE, TenantContextHolder.getTenantId(), Duration.ofMillis(durationMs));
            }
            if (alertAggregator != null) {
                alertAggregator.onJobFailure(jobName, rawParam, traceId, durationMs, t);
            }
            throw t;
        } finally {
            SnailJobExecutionContext ctx = (executionContext != null)
                    ? executionContext
                    : new SnailJobExecutionContext(pjp.getTarget(), specificMethod, (resolvedArgs != null ? resolvedArgs : pjp.getArgs()), metadata);
            triggerAfterCompletion(executedInterceptors, ctx, executionResult, errorThrown);
            if (drainStateMachine != null) {
                drainStateMachine.decrementInFlightJob();
            }
            try {
                if (jobArgs != null && !snailJobContext.getChangeWfContext().isEmpty()) {
                    for (java.util.Map.Entry<String, Object> entry : snailJobContext.getChangeWfContext().entrySet()) {
                        jobArgs.appendContext(entry.getKey(), entry.getValue());
                    }
                }
            } finally {
                SnailJobContextHolder.remove();
            }

            SnailLocalWatchdog.WatchdogSession session = sessionRef.get();
            session.cancel();
            if (session.isTimedOut()) {
                Thread.interrupted(); // 清理当前线程残留的中断状态，防止污染下一轮执行
            }

            // 还原多租户上下文
            TenantContextHolder.restore(previousTenantSnapshot);

            // 还原操作人上下文
            if (previousUser != null) {
                UserContextHolder.set(previousUser);
            } else {
                UserContextHolder.clear();
            }

            // 还原 MDC 链路追踪上下文
            if (previousTraceId != null) {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            } else {
                MDC.remove(TRACE_ID_KEY);
            }
            if (previousTaskName != null) {
                MDC.put(TASK_NAME_KEY, previousTaskName);
            } else {
                MDC.remove(TASK_NAME_KEY);
            }
        }
    }

    /**
     * 根据方法形参声明，解析并完成强类型参数绑定
     *
     * @param method   被调度的业务方法
     * @param rawArgs  AOP 原始实参列表
     * @param rawParam 原始文本任务参数
     * @param jobArgs  JobArgs 实例
     * @return 解析并转换后的强类型实参列表
     */
    protected Object[] resolveArguments(Method method, Object[] rawArgs, String rawParam, JobArgs jobArgs) {
        return resolveArguments(method, rawArgs, rawParam, jobArgs, SnailJobContextHolder.get());
    }

    /**
     * 根据方法形参声明，解析并完成强类型参数绑定（支持 SnailJobContext 与 ShardingContext 注入）
     *
     * @param method          被调度的业务方法
     * @param rawArgs         AOP 原始实参列表
     * @param rawParam        原始文本任务参数
     * @param jobArgs         JobArgs 实例
     * @param snailJobContext 当前线程持有的增强上下文
     * @return 解析并转换后的强类型实参列表
     */
    protected Object[] resolveArguments(Method method, Object[] rawArgs, String rawParam, JobArgs jobArgs, SnailJobContext snailJobContext) {
        Parameter[] parameters = method.getParameters();
        if (parameters == null || parameters.length == 0) {
            return new Object[0];
        }

        Object[] resolved = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> type = param.getType();
            Object rawArg = (rawArgs != null && i < rawArgs.length) ? rawArgs[i] : null;

            // 1. JobArgs 参数注入 (兼容 MapArgs、ReduceArgs、MergeReduceArgs、ShardingJobArgs)
            if (JobArgs.class.isAssignableFrom(type)) {
                if (rawArg instanceof JobArgs explicitArgs) {
                    resolved[i] = explicitArgs;
                } else if (jobArgs != null && type.isInstance(jobArgs)) {
                    resolved[i] = jobArgs;
                } else if (jobArgs != null && JobArgs.class.equals(type)) {
                    resolved[i] = jobArgs;
                } else {
                    try {
                        JobArgs ja = (JobArgs) type.getDeclaredConstructor().newInstance();
                        ja.setJobParams(rawParam);
                        resolved[i] = ja;
                    } catch (Exception e) {
                        JobArgs ja = new JobArgs();
                        ja.setJobParams(rawParam);
                        resolved[i] = ja;
                    }
                }
            } else if (JobContext.class.isAssignableFrom(type)) {
                // 2. JobContext 参数注入
                if (rawArg instanceof JobContext jc) {
                    resolved[i] = jc;
                } else {
                    resolved[i] = (snailJobContext != null) ? snailJobContext.getNativeJobContext() : null;
                }
            } else if (SnailJobContext.class.isAssignableFrom(type)) {
                // 3. SnailJobContext 增强门面参数注入
                resolved[i] = (snailJobContext != null) ? snailJobContext : SnailJobContext.current();
            } else if (ShardingContext.class.isAssignableFrom(type)) {
                // 4. ShardingContext 分片上下文参数注入
                resolved[i] = (snailJobContext != null) ? snailJobContext.getSharding() : ShardingContext.current();
            } else if (MapHandler.class.isAssignableFrom(type)) {
                // 5. MapHandler 参数注入 (MapReduce 特有)
                resolved[i] = (rawArg instanceof MapHandler mh) ? mh : null;
            } else if (String.class.isAssignableFrom(type)) {
                // 6. 原生字符串参数注入
                if (StringUtils.hasText(rawParam)) {
                    resolved[i] = rawParam;
                } else if (rawArg instanceof String str) {
                    resolved[i] = str;
                } else if (rawArg != null) {
                    resolved[i] = String.valueOf(rawArg);
                } else {
                    resolved[i] = null;
                }
            } else {
                // 7. 强类型 DTO 反序列化注入 (优先使用匹配的目标类型实参)
                if (rawArg != null && type.isInstance(rawArg)) {
                    resolved[i] = rawArg;
                } else if (StringUtils.hasText(rawParam)) {
                    try {
                        JavaType javaType = JsonUtil.getObjectMapper().constructType(param.getParameterizedType());
                        resolved[i] = JsonUtil.getObjectMapper().readValue(rawParam, javaType);
                    } catch (Exception e) {
                        String errorMsg = "SnailJob 参数反序列化失败: 无法将入参 [" + rawParam + "] 转换为目标类型 [" + type.getName() + "]: " + e.getMessage();
                        throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), errorMsg, e);
                    }
                } else {
                    resolved[i] = null;
                }
            }
        }
        return resolved;
    }

    /**
     * 对已绑定的任务实参执行 JSR-303 格式校验与 Fail-Fast 防卫
     *
     * @param target       目标业务 Bean 实例
     * @param method       被调度的业务方法
     * @param parameters   方法形参定义
     * @param resolvedArgs 已绑定的实参列表
     * @param jobName      任务名称
     */
    protected void validateParameters(Object target, Method method, Parameter[] parameters, Object[] resolvedArgs, String jobName) {
        if (parameters == null || parameters.length == 0) {
            return;
        }

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Object arg = (resolvedArgs != null && i < resolvedArgs.length) ? resolvedArgs[i] : null;
            if (JobArgs.class.isAssignableFrom(param.getType()) 
                    || JobContext.class.isAssignableFrom(param.getType()) 
                    || MapHandler.class.isAssignableFrom(param.getType())
                    || SnailJobContext.class.isAssignableFrom(param.getType())
                    || ShardingContext.class.isAssignableFrom(param.getType())) {
                continue;
            }

            boolean shouldValidate = shouldValidateParameter(param);
            if (shouldValidate) {
                Class<?>[] groups = resolveValidationGroups(param);
                if (arg == null) {
                    String errorMsg = "JSR-303 参数校验失败: 任务参数 [" + param.getName() + "] 不能为空";
                    throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), errorMsg);
                }
                try {
                    ValidatorUtil.validate(arg, groups);
                } catch (BusinessException be) {
                    String errorMsg = "JSR-303 参数校验失败: " + be.getMessage();
                    throw new BusinessException(be.getCode(), errorMsg, be);
                }
            }
        }
    }

    /**
     * 判断形参是否需要执行 JSR-303 格式校验
     */
    protected boolean shouldValidateParameter(Parameter param) {
        if (param.isAnnotationPresent(Valid.class)) {
            return true;
        }
        for (Annotation ann : param.getAnnotations()) {
            String name = ann.annotationType().getName();
            if ("org.springframework.validation.annotation.Validated".equals(name)) {
                return true;
            }
            if (ann.annotationType().isAnnotationPresent(Constraint.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从形参注解中提取校验分组
     */
    protected Class<?>[] resolveValidationGroups(Parameter param) {
        for (Annotation ann : param.getAnnotations()) {
            if ("org.springframework.validation.annotation.Validated".equals(ann.annotationType().getName())) {
                try {
                    Method valueMethod = ann.annotationType().getMethod("value");
                    Object val = valueMethod.invoke(ann);
                    if (val instanceof Class<?>[] classes) {
                        return classes;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return new Class<?>[0];
    }

    /**
     * 从参数对象列表中反射抽取指定 Getter 属性值（按名称顺序优先匹配，支持实体对象与 JSON 文本）
     *
     * @param args 参数对象列表
     * @param getterNames getter 方法名候选列表（如 "getTenantId", "getUserId" 等）
     * @return 提取出的属性文本值，未命中返回 null
     */
    public static String extractPropertyFromArgs(Object[] args, String... getterNames) {
        if (args == null || args.length == 0 || getterNames == null || getterNames.length == 0) {
            return null;
        }
        for (Object arg : args) {
            if (arg != null && !(arg instanceof String) && !(arg instanceof JobArgs) && !(arg instanceof JobContext)) {
                for (String getterName : getterNames) {
                    try {
                        Method m = arg.getClass().getMethod(getterName);
                        Object res = m.invoke(arg);
                        if (res != null) {
                            return String.valueOf(res);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } else if (arg instanceof String str && str.trim().startsWith("{") && str.trim().endsWith("}")) {
                try {
                    JsonNode node = JsonUtil.readTree(str);
                    if (node != null) {
                        for (String getterName : getterNames) {
                            String prop = getterName.startsWith("get")
                                    ? Character.toLowerCase(getterName.charAt(3)) + getterName.substring(4)
                                    : getterName;
                            if (node.hasNonNull(prop)) {
                                return node.get(prop).asText();
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 从解析后的实参列表中提取 tenantId (反射探测 getTenantId)
     */
    protected String extractTenantIdFromArgs(Object[] args) {
        return extractPropertyFromArgs(args, "getTenantId");
    }

    /**
     * 解析任务超时时限 (优先方法级注解，其次全局默认配置)
     */
    protected Duration resolveTimeout(AtengSnailJob atengSnailJob) {
        if (atengSnailJob != null && StringUtils.hasText(atengSnailJob.timeout())) {
            Duration duration = parseDuration(atengSnailJob.timeout());
            if (duration != null) {
                return duration;
            }
        }
        if (properties != null && properties.getDefaultJobTimeout() != null) {
            return properties.getDefaultJobTimeout();
        }
        return null;
    }

    /**
     * 解析 Duration 字符串 (支持 30s, 1m, 500ms, 2h 等标准格式)
     */
    public static Duration parseDuration(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return DurationStyle.detectAndParse(trimmed, ChronoUnit.SECONDS);
        } catch (Exception e) {
            try {
                return Duration.ofSeconds(Long.parseLong(trimmed));
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("无法将 [" + text + "] 解析为有效的 Duration 时间格式，示例: 30s, 1m, 500ms", e);
            }
        }
    }

    /**
     * 从任务参数中解析 traceId
     */
    protected String extractTraceId(String rawParam, Object[] args) {
        if (StringUtils.hasText(rawParam)) {
            String trimmed = rawParam.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    JsonNode jsonNode = JsonUtil.readTree(trimmed);
                    if (jsonNode != null) {
                        if (jsonNode.hasNonNull("traceId")) {
                            return jsonNode.get("traceId").asText();
                        }
                        if (jsonNode.hasNonNull("trace_id")) {
                            return jsonNode.get("trace_id").asText();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (trimmed.contains("traceId=") || trimmed.contains("trace_id=")) {
                for (String pair : trimmed.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim();
                        if ("traceId".equalsIgnoreCase(key) || "trace_id".equalsIgnoreCase(key)) {
                            return kv[1].trim();
                        }
                    }
                }
            }
        }
        return extractPropertyFromArgs(args, "getTraceId");
    }

    /**
     * 从任务参数中解析 userId / user
     */
    protected String extractUserId(String rawParam, Object[] args) {
        if (StringUtils.hasText(rawParam)) {
            String trimmed = rawParam.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    JsonNode jsonNode = JsonUtil.readTree(trimmed);
                    if (jsonNode != null) {
                        if (jsonNode.hasNonNull("userId")) {
                            return jsonNode.get("userId").asText();
                        }
                        if (jsonNode.hasNonNull("user_id")) {
                            return jsonNode.get("user_id").asText();
                        }
                        if (jsonNode.hasNonNull("user")) {
                            return jsonNode.get("user").asText();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return extractPropertyFromArgs(args, "getUserId", "getUser");
    }

    /**
     * 从任务参数中解析 tenantId
     */
    protected String extractTenantId(String rawParam, Object[] args) {
        if (StringUtils.hasText(rawParam)) {
            String trimmed = rawParam.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    JsonNode jsonNode = JsonUtil.readTree(trimmed);
                    if (jsonNode != null) {
                        if (jsonNode.hasNonNull("tenantId")) {
                            return jsonNode.get("tenantId").asText();
                        }
                        if (jsonNode.hasNonNull("tenant_id")) {
                            return jsonNode.get("tenant_id").asText();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (trimmed.contains("tenantId=") || trimmed.contains("tenant_id=")) {
                for (String pair : trimmed.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim();
                        if ("tenantId".equalsIgnoreCase(key) || "tenant_id".equalsIgnoreCase(key)) {
                            return kv[1].trim();
                        }
                    }
                }
            }
        }
        return extractPropertyFromArgs(args, "getTenantId");
    }

    /**
     * 解析任务标识名称（支持普通调度任务与 Map / Reduce / MergeReduce 复合命名）
     */
    protected String resolveJobName(Method specificMethod, Class<?> targetClass, AtengSnailJob atengSnailJob, JobArgs jobArgs) {
        MapExecutor mapExecutor = AnnotatedElementUtils.findMergedAnnotation(specificMethod, MapExecutor.class);
        ReduceExecutor reduceExecutor = AnnotatedElementUtils.findMergedAnnotation(specificMethod, ReduceExecutor.class);
        MergeReduceExecutor mergeReduceExecutor = AnnotatedElementUtils.findMergedAnnotation(specificMethod, MergeReduceExecutor.class);

        boolean isSlice = mapExecutor != null || reduceExecutor != null || mergeReduceExecutor != null
                || "doJobMapExecute".equals(specificMethod.getName())
                || "doReduceExecute".equals(specificMethod.getName())
                || "doMergeReduceExecute".equals(specificMethod.getName())
                || (jobArgs instanceof MapArgs)
                || (jobArgs instanceof ReduceArgs)
                || (jobArgs instanceof MergeReduceArgs);

        if (isSlice) {
            String taskName;
            if (reduceExecutor != null || "doReduceExecute".equals(specificMethod.getName()) || (jobArgs instanceof ReduceArgs)) {
                taskName = "reduce";
            } else if (mergeReduceExecutor != null || "doMergeReduceExecute".equals(specificMethod.getName()) || (jobArgs instanceof MergeReduceArgs)) {
                taskName = "mergeReduce";
            } else {
                if (mapExecutor != null && StringUtils.hasText(mapExecutor.taskName())) {
                    taskName = mapExecutor.taskName().trim();
                } else if (jobArgs instanceof MapArgs ma && StringUtils.hasText(ma.getTaskName())) {
                    taskName = ma.getTaskName().trim();
                } else {
                    taskName = "map";
                }
            }

            if (atengSnailJob != null && StringUtils.hasText(atengSnailJob.value())) {
                String declared = atengSnailJob.value().trim();
                if (declared.contains(":")) {
                    return declared;
                }
                return declared + ":" + taskName;
            }

            String enclosingJobName = resolveEnclosingJobName(specificMethod, targetClass, jobArgs);
            if (enclosingJobName.endsWith(":" + taskName)) {
                return enclosingJobName;
            }
            return enclosingJobName + ":" + taskName;
        }

        if (atengSnailJob != null && StringUtils.hasText(atengSnailJob.value())) {
            return atengSnailJob.value().trim();
        }
        JobExecutor jobExecutor = AnnotatedElementUtils.findMergedAnnotation(specificMethod, JobExecutor.class);
        if (jobExecutor == null) {
            jobExecutor = AnnotatedElementUtils.findMergedAnnotation(targetClass, JobExecutor.class);
        }
        if (jobExecutor != null && StringUtils.hasText(jobExecutor.name())) {
            return jobExecutor.name().trim();
        }
        if (jobArgs != null && StringUtils.hasText(jobArgs.getExecutorInfo())) {
            return jobArgs.getExecutorInfo().trim();
        }
        return specificMethod.getName();
    }

    /**
     * 解析切片任务所属的主任务名称
     */
    protected String resolveEnclosingJobName(Method specificMethod, Class<?> targetClass, JobArgs jobArgs) {
        // 1. 类级别 @AtengSnailJob
        AtengSnailJob classAteng = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengSnailJob.class);
        if (classAteng != null && StringUtils.hasText(classAteng.value())) {
            return classAteng.value().trim();
        }
        // 2. 类级别 @JobExecutor
        JobExecutor classJobExecutor = AnnotatedElementUtils.findMergedAnnotation(targetClass, JobExecutor.class);
        if (classJobExecutor != null && StringUtils.hasText(classJobExecutor.name())) {
            return classJobExecutor.name().trim();
        }
        // 3. jobArgs.getExecutorInfo()
        if (jobArgs != null && StringUtils.hasText(jobArgs.getExecutorInfo())) {
            return jobArgs.getExecutorInfo().trim();
        }
        // 4. 扫描 targetClass 中的 @JobExecutor 方法
        for (Method method : targetClass.getDeclaredMethods()) {
            JobExecutor methodExecutor = AnnotatedElementUtils.findMergedAnnotation(method, JobExecutor.class);
            if (methodExecutor != null && StringUtils.hasText(methodExecutor.name())) {
                return methodExecutor.name().trim();
            }
            AtengSnailJob methodAteng = AnnotatedElementUtils.findMergedAnnotation(method, AtengSnailJob.class);
            if (methodAteng != null && StringUtils.hasText(methodAteng.value())) {
                return methodAteng.value().trim();
            }
        }
        // 5. 回退使用 targetClass.getSimpleName()
        return targetClass.getSimpleName();
    }

    private Object createGovernorSkippedResult(Method method, String jobName) {
        return createSuccessResultForType(method, "[SKIPPED] 任务调度已被节点级运行时治理总线暂停: " + jobName);
    }

    private Object createInterceptedSuccessResult(Method method, SnailJobInterceptor interceptor) {
        return createSuccessResultForType(method, "[INTERCEPTED] 任务调度被拦截器跳过执行: " + interceptor.getClass().getSimpleName());
    }

    private Object createFallbackSuccessResult(Method method, String fallbackMethodName) {
        return createSuccessResultForType(method, "[FALLBACK] 任务超时就地降级补偿完成: " + fallbackMethodName);
    }

    private Object createSuccessResultForType(Method method, String message) {
        Class<?> returnType = method.getReturnType();
        if (returnType.equals(Void.TYPE)) {
            return null;
        }
        if (ExecuteResult.class.isAssignableFrom(returnType) || returnType.equals(Object.class)) {
            ExecuteResult result = ExecuteResult.success(message);
            result.setMessage(message);
            return result;
        }
        if (String.class.equals(returnType)) {
            return message;
        }
        // 基本原生类型防拆箱 NullPointerException 确定性安全默认值
        if (returnType.isPrimitive()) {
            if (returnType.equals(boolean.class)) {
                return Boolean.TRUE;
            }
            if (returnType.equals(byte.class)) {
                return (byte) 0;
            }
            if (returnType.equals(short.class)) {
                return (short) 0;
            }
            if (returnType.equals(int.class)) {
                return 0;
            }
            if (returnType.equals(long.class)) {
                return 0L;
            }
            if (returnType.equals(float.class)) {
                return 0.0f;
            }
            if (returnType.equals(double.class)) {
                return 0.0d;
            }
            if (returnType.equals(char.class)) {
                return '\0';
            }
        }
        return null;
    }

    private boolean isResultSuccess(Object result) {
        if (result == null) {
            return true;
        }
        if (result instanceof ExecuteResult er) {
            return er.getStatus() == StatusEnum.YES.getStatus();
        }
        if (result instanceof Boolean b) {
            return b;
        }
        return true;
    }

    private void triggerGlobalFallbackHandlers(SnailJobExecutionMetadata metadata, Duration elapsed) {
        if (fallbackHandlers == null || fallbackHandlers.isEmpty()) {
            return;
        }
        for (JobTimeoutFallbackHandler handler : fallbackHandlers) {
            try {
                handler.onTimeout(metadata, elapsed);
            } catch (Throwable ex) {
                log.error("[SnailJob] 全局超时降级处理器 [{}] 执行异常: {}",
                        handler.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        }
    }

    /**
     * 智能反射匹配并执行当前 Bean 上的方法级超时就地降级补偿逻辑
     *
     * @param target             目标 Bean 实例
     * @param targetClass        目标类 Class
     * @param fallbackMethodName 声明的降级方法名
     * @param metadata           任务执行上下文元数据
     * @param elapsed            超时已耗时
     * @param error              导致超时的原始异常或中断异常
     * @param resolvedArgs       已解析绑定的业务方法实参
     * @param rawArgs            AOP 原始调用实参
     * @return 降级方法执行结果
     * @throws Throwable 降级方法执行抛出的真实业务异常
     */
    protected Object invokeFallbackMethod(
            Object target,
            Class<?> targetClass,
            String fallbackMethodName,
            SnailJobExecutionMetadata metadata,
            Duration elapsed,
            Throwable error,
            Object[] resolvedArgs,
            Object[] rawArgs) throws Throwable {
        List<Method> candidates = new ArrayList<>();
        Class<?> searchType = targetClass;
        while (searchType != null && searchType != Object.class) {
            Method[] methods = searchType.isInterface() ? searchType.getMethods() : searchType.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().equals(fallbackMethodName)) {
                    candidates.add(method);
                }
            }
            searchType = searchType.getSuperclass();
        }

        if (candidates.isEmpty()) {
            throw new NoSuchMethodException("在目标类 [" + targetClass.getName() + "] 中未找到降级补偿方法: " + fallbackMethodName);
        }

        Method bestMethod = null;
        Object[] bestArgs = null;
        int maxScore = -1;

        for (Method candidate : candidates) {
            Parameter[] parameters = candidate.getParameters();
            Object[] matched = new Object[parameters.length];
            boolean matchSuccess = true;
            int score = 0;

            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                Class<?> type = param.getType();
                Object argValue = null;
                boolean found = false;

                if (SnailJobExecutionMetadata.class.isAssignableFrom(type)) {
                    argValue = metadata;
                    found = true;
                    score += 10;
                } else if (Duration.class.isAssignableFrom(type)) {
                    argValue = elapsed;
                    found = true;
                    score += 10;
                } else if (Throwable.class.isAssignableFrom(type)) {
                    argValue = error;
                    found = true;
                    score += 10;
                } else if (SnailJobContext.class.isAssignableFrom(type)) {
                    argValue = SnailJobContextHolder.get();
                    found = true;
                    score += 5;
                } else if (JobContext.class.isAssignableFrom(type)) {
                    argValue = (SnailJobContextHolder.get() != null) ? SnailJobContextHolder.get().getNativeJobContext() : null;
                    found = true;
                    score += 5;
                } else if (ShardingContext.class.isAssignableFrom(type)) {
                    argValue = (SnailJobContextHolder.get() != null) ? SnailJobContextHolder.get().getSharding() : null;
                    found = true;
                    score += 5;
                } else {
                    Object matchedArg = findArgByType(type, resolvedArgs);
                    if (matchedArg == null) {
                        matchedArg = findArgByType(type, rawArgs);
                    }
                    if (matchedArg != null) {
                        argValue = matchedArg;
                        found = true;
                        score += 5;
                    } else if (!type.isPrimitive()) {
                        argValue = null;
                        found = true;
                    }
                }

                if (!found) {
                    matchSuccess = false;
                    break;
                }
                matched[i] = argValue;
            }

            if (matchSuccess && (bestMethod == null || score > maxScore || (score == maxScore && parameters.length > bestMethod.getParameterCount()))) {
                bestMethod = candidate;
                bestArgs = matched;
                maxScore = score;
            }
        }

        if (bestMethod == null) {
            throw new IllegalStateException("在目标类 [" + targetClass.getName() + "] 中未找到可与上下文参数相匹配的降级补偿方法: " + fallbackMethodName);
        }

        ReflectionUtils.makeAccessible(bestMethod);
        try {
            return bestMethod.invoke(target, bestArgs);
        } catch (InvocationTargetException ite) {
            throw ite.getTargetException();
        }
    }

    private Object findArgByType(Class<?> type, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        for (Object arg : args) {
            if (arg != null && type.isInstance(arg)) {
                return arg;
            }
        }
        return null;
    }

    private void triggerAfterCompletion(
            java.util.List<SnailJobInterceptor> executedInterceptors,
            SnailJobExecutionContext context,
            Object result,
            Throwable ex) {
        if (executedInterceptors == null || executedInterceptors.isEmpty()) {
            return;
        }
        for (int i = executedInterceptors.size() - 1; i >= 0; i--) {
            try {
                executedInterceptors.get(i).afterCompletion(context, result, ex);
            } catch (Throwable t) {
                log.error("[SnailJob] 拦截器 [{}] afterCompletion 执行异常: {}",
                        executedInterceptors.get(i).getClass().getSimpleName(), t.getMessage(), t);
            }
        }
    }
}
