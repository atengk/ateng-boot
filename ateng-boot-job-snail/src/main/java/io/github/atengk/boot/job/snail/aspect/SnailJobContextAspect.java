package io.github.atengk.boot.job.snail.aspect;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.common.core.model.JobContext;
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
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
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
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
    public static final String DEFAULT_JOB_USER = "SYSTEM";

    private final SnailLocalWatchdog watchdog;
    private final SnailJobProperties properties;

    public SnailJobContextAspect() {
        this(null, null);
    }

    public SnailJobContextAspect(SnailLocalWatchdog watchdog, SnailJobProperties properties) {
        this.watchdog = watchdog;
        this.properties = properties;
    }

    @Around("@annotation(com.aizuda.snailjob.client.job.core.annotation.JobExecutor) || " +
            "@annotation(io.github.atengk.boot.job.snail.annotation.AtengSnailJob) || " +
            "@within(com.aizuda.snailjob.client.job.core.annotation.JobExecutor) || " +
            "@within(io.github.atengk.boot.job.snail.annotation.AtengSnailJob) || " +
            "execution(* com.aizuda.snailjob.client.job.core.IJobExecutor+.jobExecute(..))")
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

        // 2. MDC traceId 上下文编排
        String previousTraceId = MDC.get(TRACE_ID_KEY);
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
            log.debug("SnailJob 任务处于多租户遍历模式 (ITERATE)");
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

        try {
            // A. 解析方法形参并完成强类型绑定 (JobArgs、JobContext、DTO、String)
            Object[] resolvedArgs = resolveArguments(specificMethod, pjp.getArgs(), rawParam, jobArgs);

            // B. 若租户模式为 PARAM 且前面未提取到租户，从反序列化后的实参中二次探测
            if (tenantMode == JobTenantMode.PARAM && !StringUtils.hasText(TenantContextHolder.getTenantId())) {
                String tenantIdFromResolved = extractTenantIdFromArgs(resolvedArgs);
                if (StringUtils.hasText(tenantIdFromResolved)) {
                    TenantContextHolder.setTenantId(tenantIdFromResolved);
                }
            }

            // C. JSR-303 参数校验与 Fail-Fast 快速失败防卫
            validateParameters(pjp.getTarget(), specificMethod, specificMethod.getParameters(), resolvedArgs, jobName);

            // D. 挂载看门狗
            if (watchdog != null && timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                SnailLocalWatchdog.WatchdogSession session = watchdog.startWatch(Thread.currentThread(), timeout, metadata);
                sessionRef.set(session);
            }

            // E. 执行调度逻辑
            return (resolvedArgs != null && resolvedArgs.length > 0) ? pjp.proceed(resolvedArgs) : pjp.proceed();

        } catch (Throwable t) {
            SnailLocalWatchdog.WatchdogSession session = sessionRef.get();
            if (session.isTimedOut() || (t instanceof InterruptedException && session.isTimedOut())) {
                log.warn("SnailJob 任务 [{}] 本地硬超时 (阈值: {}) 并已被中断", jobName, timeout);
            }
            throw t;
        } finally {
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
        Parameter[] parameters = method.getParameters();
        if (parameters == null || parameters.length == 0) {
            return new Object[0];
        }

        Object[] resolved = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> type = param.getType();
            Object rawArg = (rawArgs != null && i < rawArgs.length) ? rawArgs[i] : null;

            // 1. JobArgs 参数注入
            if (JobArgs.class.isAssignableFrom(type)) {
                if (rawArg instanceof JobArgs explicitArgs) {
                    resolved[i] = explicitArgs;
                } else if (jobArgs != null) {
                    resolved[i] = jobArgs;
                } else {
                    JobArgs ja = new JobArgs();
                    ja.setJobParams(rawParam);
                    resolved[i] = ja;
                }
            } else if (JobContext.class.isAssignableFrom(type)) {
                // 2. JobContext 参数注入
                if (rawArg instanceof JobContext jc) {
                    resolved[i] = jc;
                } else {
                    resolved[i] = null;
                }
            } else if (String.class.isAssignableFrom(type)) {
                // 3. 原生字符串参数注入
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
                // 4. 强类型 DTO 反序列化注入
                if (StringUtils.hasText(rawParam)) {
                    try {
                        JavaType javaType = JsonUtil.getObjectMapper().constructType(param.getParameterizedType());
                        resolved[i] = JsonUtil.getObjectMapper().readValue(rawParam, javaType);
                    } catch (Exception e) {
                        String errorMsg = "SnailJob 参数反序列化失败: 无法将入参 [" + rawParam + "] 转换为目标类型 [" + type.getName() + "]: " + e.getMessage();
                        log.error(errorMsg, e);
                        throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), errorMsg, e);
                    }
                } else if (rawArg != null && type.isInstance(rawArg)) {
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
            Object arg = (resolvedArgs != null && i < resolvedArgs.length) ? resolvedArgs[i] : null;
            if (JobArgs.class.isAssignableFrom(param.getType()) || JobContext.class.isAssignableFrom(param.getType())) {
                continue;
            }

            boolean shouldValidate = shouldValidateParameter(param);
            if (shouldValidate) {
                Class<?>[] groups = resolveValidationGroups(param);
                if (arg == null) {
                    String errorMsg = "JSR-303 参数校验失败: 任务参数 [" + param.getName() + "] 不能为空";
                    log.error("SnailJob 任务 [{}] 参数校验失败: {}", jobName, errorMsg);
                    throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), errorMsg);
                }
                try {
                    ValidatorUtil.validate(arg, groups);
                } catch (BusinessException be) {
                    String errorMsg = "JSR-303 参数校验失败: " + be.getMessage();
                    log.error("SnailJob 任务 [{}] 参数校验失败: {}", jobName, errorMsg);
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
     * 从参数对象列表中反射抽取指定 Getter 属性值（按名称顺序优先匹配）
     */
    protected String extractPropertyFromArgs(Object[] args, String... getterNames) {
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
     * 解析任务标识名称
     */
    protected String resolveJobName(Method specificMethod, Class<?> targetClass, AtengSnailJob atengSnailJob, JobArgs jobArgs) {
        if (atengSnailJob != null && StringUtils.hasText(atengSnailJob.value())) {
            return atengSnailJob.value();
        }
        JobExecutor jobExecutor = AnnotatedElementUtils.findMergedAnnotation(specificMethod, JobExecutor.class);
        if (jobExecutor == null) {
            jobExecutor = AnnotatedElementUtils.findMergedAnnotation(targetClass, JobExecutor.class);
        }
        if (jobExecutor != null && StringUtils.hasText(jobExecutor.name())) {
            return jobExecutor.name();
        }
        if (jobArgs != null && StringUtils.hasText(jobArgs.getExecutorInfo())) {
            return jobArgs.getExecutorInfo();
        }
        return specificMethod.getName();
    }
}
