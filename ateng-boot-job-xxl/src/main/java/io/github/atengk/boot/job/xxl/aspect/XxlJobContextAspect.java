package io.github.atengk.boot.job.xxl.aspect;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.job.xxl.alert.XxlJobAlertAggregator;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.enums.JobTenantMode;
import io.github.atengk.boot.job.xxl.fallback.JobTimeoutFallbackHandler;
import io.github.atengk.boot.job.xxl.governor.JobRuntimeGovernor;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobExecutionContext;
import io.github.atengk.boot.job.xxl.interceptor.XxlJobInterceptor;
import io.github.atengk.boot.job.xxl.metrics.XxlJobMetricsBinder;
import io.github.atengk.boot.job.xxl.model.ShardingContext;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.timeout.AdminTimeoutRegistry;
import io.github.atengk.boot.job.xxl.watchdog.JobExecutionMetadata;
import io.github.atengk.boot.job.xxl.watchdog.LocalWatchdog;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * XXL-Job 调度任务上下文、链路追踪、形参注入与受管超时看门狗切面
 *
 * <p>无感拦截由 {@link XxlJob} 或 {@link AtengXxlJob} 声明的调度任务执行方法，在任务执行前自动织入：
 * <ul>
 *     <li><b>MDC 链路追踪</b>：提取任务入参中的 traceId 或基于无锁算法生成 32 位唯一标识，写入 MDC；</li>
 *     <li><b>多租户隔离策略</b>：解析 {@link JobTenantMode}，按需标记 {@code IGNORE} 忽略租户过滤或从入参中提取 {@code tenantId}；</li>
 *     <li><b>操作人上下文</b>：自动绑定调度中心或系统身份标识（默认为 {@code SYSTEM}）；</li>
 *     <li><b>方法形参智能绑定</b>：支持自动注入 {@link ShardingContext} 分片上下文、反序列化强类型 DTO 及原生字符串实参；</li>
 *     <li><b>JSR-303 参数校验</b>：针对挂载 {@code @Valid}、{@code @Validated} 或约束注解的方法形参实施 Fail-Fast 防卫；</li>
 *     <li><b>本地硬超时看门狗</b>：解析方法级 {@link AtengXxlJob#timeout()} 或 Admin 超时，超时时强中断工作线程并广播事件。</li>
 * </ul>
 * 并在任务完成后无论成功或异常，严格执行现场恢复与现场清理，彻底杜绝线程池复用污染与上下文泄露。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class XxlJobContextAspect {

    private static final Logger log = LoggerFactory.getLogger(XxlJobContextAspect.class);

    public static final String TRACE_ID_KEY = "traceId";
    public static final String DEFAULT_JOB_USER = "SYSTEM";

    private static final Map<Class<?>, Object> DEFAULT_PRIMITIVE_VALUES = Map.of(
            boolean.class, false,
            byte.class, (byte) 0,
            short.class, (short) 0,
            int.class, 0,
            long.class, 0L,
            float.class, 0.0f,
            double.class, 0.0d,
            char.class, '\0'
    );

    private final LocalWatchdog watchdog;
    private final XxlJobProperties properties;
    private final Executor taskExecutor;
    private final XxlJobAlertAggregator alertAggregator;
    private final XxlJobMetricsBinder metricsBinder;
    private final List<XxlJobInterceptor> interceptors;
    private final JobRuntimeGovernor governor;
    private final List<JobTimeoutFallbackHandler> fallbackHandlers;

    public XxlJobContextAspect() {
        this(null, null, null, null, null, null, null, null);
    }

    public XxlJobContextAspect(LocalWatchdog watchdog, XxlJobProperties properties, Executor taskExecutor) {
        this(watchdog, properties, taskExecutor, null, null, null, null, null);
    }

    public XxlJobContextAspect(LocalWatchdog watchdog, XxlJobProperties properties, Executor taskExecutor, XxlJobAlertAggregator alertAggregator) {
        this(watchdog, properties, taskExecutor, alertAggregator, null, null, null, null);
    }

    public XxlJobContextAspect(
            LocalWatchdog watchdog,
            XxlJobProperties properties,
            Executor taskExecutor,
            XxlJobAlertAggregator alertAggregator,
            XxlJobMetricsBinder metricsBinder) {
        this(watchdog, properties, taskExecutor, alertAggregator, metricsBinder, null, null, null);
    }

    public XxlJobContextAspect(
            LocalWatchdog watchdog,
            XxlJobProperties properties,
            Executor taskExecutor,
            XxlJobAlertAggregator alertAggregator,
            XxlJobMetricsBinder metricsBinder,
            List<XxlJobInterceptor> interceptors) {
        this(watchdog, properties, taskExecutor, alertAggregator, metricsBinder, interceptors, null, null);
    }

    public XxlJobContextAspect(
            LocalWatchdog watchdog,
            XxlJobProperties properties,
            Executor taskExecutor,
            XxlJobAlertAggregator alertAggregator,
            XxlJobMetricsBinder metricsBinder,
            List<XxlJobInterceptor> interceptors,
            JobRuntimeGovernor governor) {
        this(watchdog, properties, taskExecutor, alertAggregator, metricsBinder, interceptors, governor, null);
    }

    public XxlJobContextAspect(
            LocalWatchdog watchdog,
            XxlJobProperties properties,
            Executor taskExecutor,
            XxlJobAlertAggregator alertAggregator,
            XxlJobMetricsBinder metricsBinder,
            List<XxlJobInterceptor> interceptors,
            JobRuntimeGovernor governor,
            List<JobTimeoutFallbackHandler> fallbackHandlers) {
        this.watchdog = watchdog;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.alertAggregator = alertAggregator;
        this.metricsBinder = metricsBinder;
        this.governor = governor;
        if (interceptors != null && !interceptors.isEmpty()) {
            List<XxlJobInterceptor> sorted = new ArrayList<>(interceptors);
            AnnotationAwareOrderComparator.sort(sorted);
            this.interceptors = Collections.unmodifiableList(sorted);
        } else {
            this.interceptors = Collections.emptyList();
        }
        if (fallbackHandlers != null && !fallbackHandlers.isEmpty()) {
            List<JobTimeoutFallbackHandler> sorted = new ArrayList<>(fallbackHandlers);
            AnnotationAwareOrderComparator.sort(sorted);
            this.fallbackHandlers = Collections.unmodifiableList(sorted);
        } else {
            this.fallbackHandlers = Collections.emptyList();
        }
    }

    @Around("@annotation(com.xxl.job.core.handler.annotation.XxlJob) || @annotation(io.github.atengk.boot.job.xxl.annotation.AtengXxlJob) || @within(io.github.atengk.boot.job.xxl.annotation.AtengXxlJob)")
    public Object aroundJob(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : method.getDeclaringClass();
        Method specificMethod = org.springframework.aop.support.AopUtils.getMostSpecificMethod(method, targetClass);

        AtengXxlJob atengXxlJob = AnnotatedElementUtils.findMergedAnnotation(specificMethod, AtengXxlJob.class);
        if (atengXxlJob == null) {
            atengXxlJob = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengXxlJob.class);
        }
        JobTenantMode tenantMode = (atengXxlJob != null) ? atengXxlJob.tenantMode() : JobTenantMode.PARAM;

        // 1. 尝试获取任务原始入参 (优先从 XxlJobHelper，回退至方法实参)
        String rawParam = XxlJobHelper.getJobParam();
        if (!StringUtils.hasText(rawParam) && pjp.getArgs() != null && pjp.getArgs().length > 0) {
            Object firstArg = pjp.getArgs()[0];
            if (firstArg instanceof String str) {
                rawParam = str;
            }
        }

        // 2. MDC traceId 上下文编排
        String previousTraceId = MDC.get(TRACE_ID_KEY);
        if (!StringUtils.hasText(previousTraceId)) {
            String extractedTraceId = extractTraceId(rawParam, pjp.getArgs());
            String traceId = StringUtils.hasText(extractedTraceId) ? extractedTraceId : IdUtil.fastSimpleUUID();
            MDC.put(TRACE_ID_KEY, traceId);
        }

        // 3. UserContextHolder 操作人上下文编排
        String previousUser = UserContextHolder.getExplicit();
        if (!StringUtils.hasText(previousUser)) {
            String extractedUser = extractUserId(rawParam, pjp.getArgs());
            UserContextHolder.set(StringUtils.hasText(extractedUser) ? extractedUser : DEFAULT_JOB_USER);
        }

        // 4. TenantContextHolder 多租户上下文编排 (第一道基于 rawParam/原始实参提取)
        TenantSnapshot previousTenantSnapshot = TenantContextHolder.capturePrevious();
        if (tenantMode == JobTenantMode.IGNORE) {
            TenantContextHolder.setIgnoreTenant(true);
        } else if (tenantMode == JobTenantMode.PARAM) {
            String tenantId = extractTenantId(rawParam, pjp.getArgs());
            if (StringUtils.hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            }
        } else if (tenantMode == JobTenantMode.ITERATE) {
            log.debug("XXL-Job 任务 [{}] 处于多租户遍历模式 (ITERATE)，租户在独立沙箱上下文中流式执行", resolveJobName(specificMethod, atengXxlJob));
        }

        // 5. 本地硬看门狗与超时时限解析
        long logId = XxlJobHelper.getLogId();
        long jobId = XxlJobHelper.getJobId();
        String jobName = resolveJobName(specificMethod, atengXxlJob);
        Duration timeout = resolveTimeout(atengXxlJob, logId);

        JobExecutionMetadata metadata = new JobExecutionMetadata(
                jobId,
                jobName,
                logId,
                MDC.get(TRACE_ID_KEY),
                TenantContextHolder.getTenantId()
        );

        AtomicReference<LocalWatchdog.WatchdogSession> sessionRef = new AtomicReference<>(LocalWatchdog.WatchdogSession.NOOP);
        AtomicBoolean fallbackTriggered = new AtomicBoolean(false);
        boolean alertEnabled = (atengXxlJob != null) ? atengXxlJob.alertEnabled() : true;
        int alertSuppressThreshold = (atengXxlJob != null) ? atengXxlJob.alertSuppressThreshold() : -1;
        long startNs = System.nanoTime();

        // 6. 执行参数解析、防卫校验与业务逻辑 (确保终态严格清理上下文)
        try {
            // 6.0. 节点级运行时治理总线判定：若该任务在当前节点被临时静默 (PAUSED)，则优雅跳过执行并在 finally 中严格还原清理上下文
            if (this.governor != null && (this.governor.isPaused(jobName) || this.governor.isPaused(specificMethod.getName()))) {
                String skipMsg = String.format("[SKIPPED] Handler [%s] is paused on local node (节点级临时静默)，已安全跳过执行并报告成功", jobName);
                log.warn(skipMsg);
                XxlJobHelper.log(skipMsg);
                XxlJobHelper.handleSuccess(skipMsg);
                return getDefaultReturnValue(specificMethod);
            }

            // A. 解析方法形参并完成强类型绑定 (ShardingContext、DTO、String)
            Object[] resolvedArgs = resolveArguments(specificMethod, pjp.getArgs(), rawParam);

            // B. 若租户模式为 PARAM 且前面未提取到租户，从反序列化后的实参中二次探测
            if (tenantMode == JobTenantMode.PARAM && !StringUtils.hasText(TenantContextHolder.getTenantId())) {
                String tenantIdFromResolved = extractTenantIdFromArgs(resolvedArgs);
                if (StringUtils.hasText(tenantIdFromResolved)) {
                    TenantContextHolder.setTenantId(tenantIdFromResolved);
                    metadata = new JobExecutionMetadata(
                            jobId,
                            jobName,
                            logId,
                            MDC.get(TRACE_ID_KEY),
                            tenantIdFromResolved
                    );
                }
            }

            // C. JSR-303 参数校验与 Fail-Fast 快速失败防卫
            validateParameters(pjp.getTarget(), specificMethod, specificMethod.getParameters(), resolvedArgs, jobName);

            // D. 挂载执行元数据与看门狗
            boolean hasWatchdog = watchdog != null && timeout != null && !timeout.isZero() && !timeout.isNegative();

            // E. 构建任务执行上下文并执行切面拦截器 preHandle
            XxlJobExecutionContext executionContext = new XxlJobExecutionContext(
                    jobName,
                    specificMethod,
                    pjp.getTarget(),
                    resolvedArgs,
                    rawParam,
                    ShardingContext.current(),
                    MDC.get(TRACE_ID_KEY),
                    TenantContextHolder.getTenantId()
            );

            int interceptorIndex = -1;
            if (!this.interceptors.isEmpty()) {
                try {
                    for (int i = 0; i < this.interceptors.size(); i++) {
                        XxlJobInterceptor interceptor = this.interceptors.get(i);
                        boolean proceed = interceptor.preHandle(executionContext);
                        if (!proceed) {
                            // 优雅拦截并终止后续执行：触发此前已通过拦截器的 afterCompletion，向调度中心返回 200 SUCCESS
                            triggerAfterCompletion(interceptorIndex, executionContext, null);
                            String interceptMsg = String.format("[INTERCEPTED] 任务 [%s] 已被拦截器 [%s] 拦截，终止执行并报告成功",
                                    jobName, interceptor.getClass().getSimpleName());
                            log.info(interceptMsg);
                            XxlJobHelper.log(interceptMsg);
                            XxlJobHelper.handleSuccess(interceptMsg);
                            return getDefaultReturnValue(specificMethod);
                        }
                        interceptorIndex = i;
                    }
                } catch (Throwable t) {
                    triggerAfterCompletion(interceptorIndex, executionContext, t);
                    throw t;
                }
            }

            // F. 执行目标任务业务逻辑，并在 finally 中确定性触发 afterCompletion
            Object result;
            Throwable executionThrowable = null;
            try {
                if (properties != null && properties.isManagedPoolEnabled() && taskExecutor != null) {
                    result = executeInManagedPool(pjp, timeout, metadata, resolvedArgs, sessionRef);
                } else {
                    if (hasWatchdog) {
                        LocalWatchdog.WatchdogSession session = watchdog.startWatch(Thread.currentThread(), timeout, metadata);
                        sessionRef.set(session);
                    }
                    result = (resolvedArgs != null && resolvedArgs.length > 0) ? pjp.proceed(resolvedArgs) : pjp.proceed();
                }

                // G. 目标任务成功执行：逆序触发 postHandle
                triggerPostHandle(interceptorIndex, executionContext, result);

            } catch (Throwable t) {
                executionThrowable = t;
                throw t;
            } finally {
                // H. 确定性逆序触发 afterCompletion (无论执行成功还是抛出异常)
                triggerAfterCompletion(interceptorIndex, executionContext, executionThrowable);
            }

            // 7. 任务执行返回：若发生过看门狗超时则补发降级补偿，触发告警与度量采集
            long durationNs = System.nanoTime() - startNs;
            LocalWatchdog.WatchdogSession successSession = sessionRef.get();
            boolean hasTimedOut = (successSession != null && successSession.isTimedOut());
            if (hasTimedOut && fallbackTriggered.compareAndSet(false, true)) {
                log.warn("XXL-Job 任务 [{}] 本地硬超时 (阈值: {}) 并已被中断", jobName, timeout);
                dispatchTimeoutFallback(pjp.getTarget(), specificMethod, atengXxlJob, metadata, Duration.ofNanos(durationNs), null);
            }
            if (alertAggregator != null) {
                long costMs = durationNs / 1_000_000;
                if (hasTimedOut) {
                    alertAggregator.onJobFailure(jobName, rawParam, MDC.get(TRACE_ID_KEY), costMs,
                            new TimeoutException("XXL-Job 任务超时 (阈值: " + timeout + ")"), alertEnabled, alertSuppressThreshold);
                } else {
                    alertAggregator.onJobSuccess(jobName, rawParam, MDC.get(TRACE_ID_KEY), costMs, alertEnabled);
                }
            }
            if (metricsBinder != null) {
                String status = hasTimedOut ? XxlJobMetricsBinder.STATUS_FAILURE : XxlJobMetricsBinder.STATUS_SUCCESS;
                metricsBinder.recordExecution(jobName, status, TenantContextHolder.getTenantId(), Duration.ofNanos(durationNs));
            }
            return result;
        } catch (Throwable t) {
            long durationNs = System.nanoTime() - startNs;
            LocalWatchdog.WatchdogSession session = sessionRef.get();
            boolean isTimedOut = (session != null && session.isTimedOut())
                    || (t instanceof InterruptedException && session != null && session.isTimedOut())
                    || (t instanceof BusinessException && session != null && session.isTimedOut());
            if (isTimedOut && fallbackTriggered.compareAndSet(false, true)) {
                log.warn("XXL-Job 任务 [{}] 本地硬超时 (阈值: {}) 并已被中断", jobName, timeout);
                dispatchTimeoutFallback(pjp.getTarget(), specificMethod, atengXxlJob, metadata, Duration.ofNanos(durationNs), t);
            }
            // 8. 任务执行异常：触发告警风暴聚合判定与 Micrometer 度量采集
            if (alertAggregator != null) {
                long costMs = durationNs / 1_000_000;
                alertAggregator.onJobFailure(jobName, rawParam, MDC.get(TRACE_ID_KEY), costMs, t, alertEnabled, alertSuppressThreshold);
            }
            if (metricsBinder != null) {
                metricsBinder.recordExecution(jobName, XxlJobMetricsBinder.STATUS_FAILURE, TenantContextHolder.getTenantId(), Duration.ofNanos(durationNs));
            }
            throw t;
        } finally {
            LocalWatchdog.WatchdogSession session = sessionRef.get();
            session.cancel();
            if (session.isTimedOut()) {
                Thread.interrupted(); // 清理当前线程残留的中断状态，防止污染下一轮执行
            }
            if (logId > 0) {
                AdminTimeoutRegistry.remove(logId);
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
        }
    }

    private Object executeInManagedPool(ProceedingJoinPoint pjp, Duration timeout, JobExecutionMetadata metadata,
                                        Object[] resolvedArgs, AtomicReference<LocalWatchdog.WatchdogSession> sessionRef) throws Throwable {
        XxlJobContext xxlJobContext = XxlJobContext.getXxlJobContext();
        CompletableFuture<Object> future = new CompletableFuture<>();
        AtomicReference<Thread> workerThreadRef = new AtomicReference<>();

        taskExecutor.execute(() -> {
            Thread workerThread = Thread.currentThread();
            workerThreadRef.set(workerThread);
            LocalWatchdog.WatchdogSession session = LocalWatchdog.WatchdogSession.NOOP;
            if (watchdog != null && timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                session = watchdog.startWatch(workerThread, timeout, metadata);
            }
            sessionRef.set(session);
            if (xxlJobContext != null) {
                XxlJobContext.setXxlJobContext(xxlJobContext);
            }
            try {
                Object result = (resolvedArgs != null && resolvedArgs.length > 0)
                        ? pjp.proceed(resolvedArgs)
                        : pjp.proceed();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                session.cancel();
                if (session.isTimedOut()) {
                    Thread.interrupted();
                }
                XxlJobContext.setXxlJobContext(null);
            }
        });

        Duration waitTimeout;
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            waitTimeout = timeout.plusSeconds(1);
        } else if (properties != null && properties.getDefaultJobTimeout() != null) {
            waitTimeout = properties.getDefaultJobTimeout();
        } else {
            waitTimeout = Duration.ofHours(1);
        }

        try {
            return future.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            Thread worker = workerThreadRef.get();
            if (worker != null) {
                try {
                    worker.interrupt();
                } catch (Throwable ignored) {
                }
            }
            LocalWatchdog.WatchdogSession session = sessionRef.get();
            if (session != null) {
                session.markTimedOut();
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "受管线程池任务执行超时 (时限: " + waitTimeout + ")", te);
        } catch (ExecutionException ee) {
            throw ee.getCause() != null ? ee.getCause() : ee;
        }
    }

    /**
     * 根据方法形参声明，解析并完成强类型参数绑定
     *
     * @param method   被调度的业务方法
     * @param rawArgs  AOP 原始实参列表
     * @param rawParam 原始文本任务参数
     * @return 解析并转换后的强类型实参列表
     */
    protected Object[] resolveArguments(Method method, Object[] rawArgs, String rawParam) {
        Parameter[] parameters = method.getParameters();
        if (parameters == null || parameters.length == 0) {
            return new Object[0];
        }

        Object[] resolved = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> type = param.getType();
            Object rawArg = (rawArgs != null && i < rawArgs.length) ? rawArgs[i] : null;

            // 1. ShardingContext 分片上下文智能注入
            if (ShardingContext.class.isAssignableFrom(type)) {
                if (rawArg instanceof ShardingContext explicitSharding && XxlJobContext.getXxlJobContext() == null) {
                    resolved[i] = explicitSharding;
                } else {
                    resolved[i] = ShardingContext.current();
                }
            } else if (String.class.isAssignableFrom(type)) {
                // 2. 原生字符串参数注入 (无需加双引号)
                if (StringUtils.hasText(rawParam)) {
                    resolved[i] = rawParam;
                } else if (rawArg != null) {
                    resolved[i] = String.valueOf(rawArg);
                } else {
                    resolved[i] = null;
                }
            } else {
                // 3. 强类型 DTO / 集合 / 复杂 POJO 反序列化
                if (StringUtils.hasText(rawParam)) {
                    try {
                        JavaType javaType = JsonUtil.getObjectMapper().constructType(param.getParameterizedType());
                        resolved[i] = JsonUtil.getObjectMapper().readValue(rawParam, javaType);
                    } catch (Exception e) {
                        String errorMsg = "XXL-Job 参数反序列化失败: 无法将入参 [" + rawParam + "] 转换为目标类型 [" + type.getName() + "]: " + e.getMessage();
                        log.error(errorMsg, e);
                        XxlJobHelper.handleFail(errorMsg);
                        XxlJobHelper.log(errorMsg);
                        throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), errorMsg, e);
                    }
                } else if (rawArg != null) {
                    resolved[i] = rawArg;
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
            Object arg = resolvedArgs[i];
            if (ShardingContext.class.isAssignableFrom(param.getType())) {
                continue;
            }

            boolean shouldValidate = shouldValidateParameter(param);
            if (shouldValidate) {
                Class<?>[] groups = resolveValidationGroups(param);
                if (arg == null) {
                    String errorMsg = "JSR-303 参数校验失败: 任务参数 [" + param.getName() + "] 不能为空";
                    log.error("XXL-Job 任务 [{}] 参数校验失败: {}", jobName, errorMsg);
                    XxlJobHelper.handleFail(errorMsg);
                    XxlJobHelper.log(errorMsg);
                    throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), errorMsg);
                }
                try {
                    ValidatorUtil.validate(arg, groups);
                } catch (BusinessException be) {
                    String errorMsg = "JSR-303 参数校验失败: " + be.getMessage();
                    log.error("XXL-Job 任务 [{}] 参数校验失败: {}", jobName, errorMsg);
                    XxlJobHelper.handleFail(errorMsg);
                    XxlJobHelper.log(errorMsg);
                    throw be;
                }
            }
        }

        // 针对形参上直接声明约束注解（如 @NotNull、@Min）的场景执行 Executable 参数级校验
        validateExecutableParameters(target, method, resolvedArgs, jobName);
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
     * 校验方法级形参约束（基于 ExecutableValidator）
     */
    protected void validateExecutableParameters(Object target, Method method, Object[] resolvedArgs, String jobName) {
        try {
            jakarta.validation.Validator validator = ValidatorUtil.getValidator();
            if (validator != null && target != null && hasExecutableConstraints(method)) {
                jakarta.validation.executable.ExecutableValidator execValidator = validator.forExecutables();
                Set<ConstraintViolation<Object>> violations = execValidator.validateParameters(target, method, resolvedArgs);
                if (!violations.isEmpty()) {
                    ConstraintViolation<Object> violation = violations.iterator().next();
                    String errorMsg = "JSR-303 参数校验失败: " + violation.getPropertyPath() + " " + violation.getMessage();
                    log.error("XXL-Job 任务 [{}] 参数校验失败: {}", jobName, errorMsg);
                    XxlJobHelper.handleFail(errorMsg);
                    XxlJobHelper.log(errorMsg);
                    throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), errorMsg);
                }
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Throwable t) {
            log.warn("执行 ExecutableValidator 参数校验时发生异常，忽略: {}", t.getMessage());
        }
    }

    /**
     * 判定方法参数是否挂载了 JSR-303 约束注解
     */
    protected boolean hasExecutableConstraints(Method method) {
        for (Parameter parameter : method.getParameters()) {
            for (Annotation ann : parameter.getAnnotations()) {
                if (ann.annotationType().isAnnotationPresent(Constraint.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从参数对象列表中反射抽取指定 Getter 属性值（按名称顺序优先匹配）
     */
    protected String extractPropertyFromArgs(Object[] args, String... getterNames) {
        if (args == null || args.length == 0 || getterNames == null || getterNames.length == 0) {
            return null;
        }
        for (Object arg : args) {
            if (arg != null && !(arg instanceof String) && !(arg instanceof ShardingContext)) {
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
     * 解析任务超时时限 (优先方法级注解，其次 Admin 动态下发，最后全局默认配置)
     */
    protected Duration resolveTimeout(AtengXxlJob atengXxlJob, long logId) {
        if (atengXxlJob != null && StringUtils.hasText(atengXxlJob.timeout())) {
            Duration duration = parseDuration(atengXxlJob.timeout());
            if (duration != null) {
                return duration;
            }
        }
        if (logId > 0) {
            Integer adminTimeoutSeconds = AdminTimeoutRegistry.get(logId);
            if (adminTimeoutSeconds != null && adminTimeoutSeconds > 0) {
                return Duration.ofSeconds(adminTimeoutSeconds);
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
                    // 忽略非严格 JSON 解析失败
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
     * 保持向后兼容的单参方法
     */
    protected String extractTraceId(String rawParam) {
        return extractTraceId(rawParam, null);
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
                    // 忽略非严格 JSON 解析失败
                }
            }
        }
        return extractPropertyFromArgs(args, "getUserId", "getUser");
    }

    /**
     * 保持向后兼容的单参方法
     */
    protected String extractUserId(String rawParam) {
        return extractUserId(rawParam, null);
    }

    /**
     * 从任务参数或实参列表中解析 tenantId
     */
    protected String extractTenantId(String rawParam, Object[] args) {
        if (StringUtils.hasText(rawParam)) {
            String trimmed = rawParam.trim();
            // 1. JSON 结构解析
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
                    // 降级为键值对或纯值解析
                }
            }

            // 2. URL 参数键值对格式解析 (如 tenantId=1001&name=test)
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

            // 3. 纯数字或无分隔符的单一字符串 (如直接传 "10001")
            if (!trimmed.contains(" ") && !trimmed.contains(":") && !trimmed.contains("{") && !trimmed.contains("&")) {
                return trimmed;
            }
        }

        // 4. 从非字符串方法参数对象中反射探测 (如自定义 DTO 包含 getTenantId)
        return extractTenantIdFromArgs(args);
    }

    /**
     * 解析任务全局唯一标识名称
     *
     * <p>优先取 {@link AtengXxlJob#value()}，其次取原生 {@link XxlJob#value()}，保底取方法名称。</p>
     */
    protected String resolveJobName(Method method, AtengXxlJob atengXxlJob) {
        if (atengXxlJob != null && StringUtils.hasText(atengXxlJob.value())) {
            return atengXxlJob.value();
        }
        XxlJob xxlJob = AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class);
        if (xxlJob != null && StringUtils.hasText(xxlJob.value())) {
            return xxlJob.value();
        }
        return method.getName();
    }

    /**
     * 逆序触发切面拦截器 postHandle 回调
     */
    private void triggerPostHandle(int maxIndex, XxlJobExecutionContext context, Object result) throws Exception {
        if (this.interceptors.isEmpty() || maxIndex < 0) {
            return;
        }
        for (int i = maxIndex; i >= 0; i--) {
            this.interceptors.get(i).postHandle(context, result);
        }
    }

    /**
     * 逆序触发切面拦截器 afterCompletion 终态清理回调
     *
     * <p>捕获单个拦截器抛出的异常并记录日志，确保后续拦截器正常执行且不掩盖原始业务异常。</p>
     */
    private void triggerAfterCompletion(int maxIndex, XxlJobExecutionContext context, Throwable throwable) {
        if (this.interceptors.isEmpty() || maxIndex < 0) {
            return;
        }
        for (int i = maxIndex; i >= 0; i--) {
            try {
                this.interceptors.get(i).afterCompletion(context, throwable);
            } catch (Throwable t) {
                log.error("执行 XXL-Job 拦截器 [{}] afterCompletion 发生异常", this.interceptors.get(i).getClass().getName(), t);
            }
        }
    }

    /**
     * 根据方法返回类型获取默认保底返回值
     */
    private Object getDefaultReturnValue(Method method) {
        return DEFAULT_PRIMITIVE_VALUES.get(method.getReturnType());
    }

    /**
     * 向受管异步线程池派发任务超时降级补偿任务
     *
     * @param target      目标业务 Bean
     * @param method      被调度的业务方法
     * @param atengXxlJob 注解配置
     * @param metadata    任务执行元数据
     * @param elapsed     实际执行耗时
     * @param throwable   触发超时的异常（可能为 null）
     */
    protected void dispatchTimeoutFallback(Object target, Method method, AtengXxlJob atengXxlJob,
                                           JobExecutionMetadata metadata, Duration elapsed, Throwable throwable) {
        if (metadata == null) {
            return;
        }

        String traceId = metadata.getTraceId();
        String tenantId = metadata.getTenantId();
        String user = UserContextHolder.getExplicit();
        XxlJobContext xxlJobContext = XxlJobContext.getXxlJobContext();

        Runnable fallbackTask = () -> {
            String prevTraceId = MDC.get(TRACE_ID_KEY);
            TenantSnapshot prevTenantSnapshot = TenantContextHolder.capturePrevious();
            String prevUser = UserContextHolder.getExplicit();
            XxlJobContext prevJobContext = XxlJobContext.getXxlJobContext();

            if (StringUtils.hasText(traceId)) {
                MDC.put(TRACE_ID_KEY, traceId);
            }
            if (StringUtils.hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            }
            if (StringUtils.hasText(user)) {
                UserContextHolder.set(user);
            }
            if (xxlJobContext != null) {
                XxlJobContext.setXxlJobContext(xxlJobContext);
            }

            try {
                executeFallback(target, method, atengXxlJob, metadata, elapsed, throwable);
            } catch (Throwable ex) {
                log.error("XXL-Job 任务 [{}] 超时降级补偿执行失败", metadata.getJobName(), ex);
            } finally {
                TenantContextHolder.restore(prevTenantSnapshot);
                if (prevUser != null) {
                    UserContextHolder.set(prevUser);
                } else {
                    UserContextHolder.clear();
                }
                if (prevTraceId != null) {
                    MDC.put(TRACE_ID_KEY, prevTraceId);
                } else {
                    MDC.remove(TRACE_ID_KEY);
                }
                XxlJobContext.setXxlJobContext(prevJobContext);
            }
        };

        if (taskExecutor != null) {
            try {
                taskExecutor.execute(fallbackTask);
            } catch (Throwable t) {
                log.error("向受管线程池提交超时降级补偿任务失败，尝试就地同步降级执行", t);
                fallbackTask.run();
            }
        } else {
            fallbackTask.run();
        }
    }

    /**
     * 执行降级补偿逻辑：优先同 Bean 上的 fallback 方法，若无则回调全局 JobTimeoutFallbackHandler SPI
     */
    protected void executeFallback(Object target, Method method, AtengXxlJob atengXxlJob,
                                   JobExecutionMetadata metadata, Duration elapsed, Throwable throwable) {
        String fallbackMethodName = (atengXxlJob != null) ? atengXxlJob.fallback() : null;
        boolean invokedBeanFallback = false;

        if (StringUtils.hasText(fallbackMethodName) && target != null) {
            Class<?> targetClass = org.springframework.aop.support.AopUtils.getTargetClass(target);
            Method fallbackMethod = findFallbackMethod(targetClass, fallbackMethodName.trim());
            if (fallbackMethod != null) {
                try {
                    fallbackMethod.setAccessible(true);
                    Object[] args = buildFallbackArgs(fallbackMethod, metadata, elapsed, throwable);
                    fallbackMethod.invoke(target, args);
                    invokedBeanFallback = true;
                    log.info("XXL-Job 任务 [{}] 超时成功执行 Bean [{}] 降级补偿方法: {}",
                            metadata.getJobName(), targetClass.getSimpleName(), fallbackMethodName);
                } catch (Throwable ex) {
                    log.error("XXL-Job 任务 [{}] 调用 Bean 降级方法 [{}] 异常", metadata.getJobName(), fallbackMethodName, ex);
                }
            } else {
                log.warn("XXL-Job 任务 [{}] 声明了降级方法 [{}]，但在 Bean [{}] 中未找到可用方法",
                        metadata.getJobName(), fallbackMethodName, targetClass.getName());
            }
        }

        // 若未声明或未能调用同 Bean 上的 fallback 方法，回调全局 SPI
        if (!invokedBeanFallback && !fallbackHandlers.isEmpty()) {
            for (JobTimeoutFallbackHandler handler : fallbackHandlers) {
                try {
                    handler.onTimeout(metadata, elapsed);
                } catch (Throwable ex) {
                    log.error("XXL-Job 任务 [{}] 回调全局超时降级处理器 [{}] 发生异常",
                            metadata.getJobName(), handler.getClass().getName(), ex);
                }
            }
        }
    }

    private Method findFallbackMethod(Class<?> targetClass, String methodName) {
        Class<?> current = targetClass;
        while (current != null && !current.equals(Object.class)) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Object[] buildFallbackArgs(Method fallbackMethod, JobExecutionMetadata metadata, Duration elapsed, Throwable throwable) {
        Class<?>[] paramTypes = fallbackMethod.getParameterTypes();
        if (paramTypes.length == 0) {
            return new Object[0];
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            if (JobExecutionMetadata.class.isAssignableFrom(pt)) {
                args[i] = metadata;
            } else if (Duration.class.isAssignableFrom(pt)) {
                args[i] = elapsed;
            } else if (Throwable.class.isAssignableFrom(pt)) {
                args[i] = throwable;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

}
