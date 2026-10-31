package io.github.atengk.boot.job.xxl.aspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.enums.JobTenantMode;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.timeout.AdminTimeoutRegistry;
import io.github.atengk.boot.job.xxl.watchdog.JobExecutionMetadata;
import io.github.atengk.boot.job.xxl.watchdog.LocalWatchdog;
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

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * XXL-Job 调度任务上下文、链路追踪与受管超时看门狗切面
 *
 * <p>无感拦截由 {@link XxlJob} 或 {@link AtengXxlJob} 声明的调度任务执行方法，在任务执行前自动织入：
 * <ul>
 *     <li><b>MDC 链路追踪</b>：提取任务入参中的 traceId 或基于无锁算法生成 32 位唯一标识，写入 MDC；</li>
 *     <li><b>多租户隔离策略</b>：解析 {@link JobTenantMode}，按需标记 {@code IGNORE} 忽略租户过滤或从入参中提取 {@code tenantId}；</li>
 *     <li><b>操作人上下文</b>：自动绑定调度中心或系统身份标识（默认为 {@code SYSTEM}）；</li>
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

    private final LocalWatchdog watchdog;
    private final XxlJobProperties properties;
    private final Executor taskExecutor;

    public XxlJobContextAspect() {
        this(null, null, null);
    }

    public XxlJobContextAspect(LocalWatchdog watchdog, XxlJobProperties properties, Executor taskExecutor) {
        this.watchdog = watchdog;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    @Around("@annotation(com.xxl.job.core.handler.annotation.XxlJob) || @annotation(io.github.atengk.boot.job.xxl.annotation.AtengXxlJob) || @within(io.github.atengk.boot.job.xxl.annotation.AtengXxlJob)")
    public Object aroundJob(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : method.getDeclaringClass();

        AtengXxlJob atengXxlJob = AnnotatedElementUtils.findMergedAnnotation(method, AtengXxlJob.class);
        if (atengXxlJob == null) {
            atengXxlJob = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengXxlJob.class);
        }
        JobTenantMode tenantMode = (atengXxlJob != null) ? atengXxlJob.tenantMode() : JobTenantMode.PARAM;

        // 1. 尝试获取任务入参 (优先从 XxlJobHelper，回退至方法实参)
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
            String extractedTraceId = extractTraceId(rawParam);
            String traceId = StringUtils.hasText(extractedTraceId) ? extractedTraceId : IdUtil.fastSimpleUUID();
            MDC.put(TRACE_ID_KEY, traceId);
        }

        // 3. UserContextHolder 操作人上下文编排
        String previousUser = UserContextHolder.getExplicit();
        if (!StringUtils.hasText(previousUser)) {
            String extractedUser = extractUserId(rawParam);
            UserContextHolder.set(StringUtils.hasText(extractedUser) ? extractedUser : DEFAULT_JOB_USER);
        }

        // 4. TenantContextHolder 多租户上下文编排
        TenantSnapshot previousTenantSnapshot = TenantContextHolder.capturePrevious();
        if (tenantMode == JobTenantMode.IGNORE) {
            TenantContextHolder.setIgnoreTenant(true);
        } else if (tenantMode == JobTenantMode.PARAM) {
            String tenantId = extractTenantId(rawParam, pjp.getArgs());
            if (StringUtils.hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            }
        }

        // 5. 本地硬看门狗与超时时限解析
        long logId = XxlJobHelper.getLogId();
        long jobId = XxlJobHelper.getJobId();
        String jobName = (atengXxlJob != null && StringUtils.hasText(atengXxlJob.value())) ? atengXxlJob.value() : method.getName();
        Duration timeout = resolveTimeout(atengXxlJob, logId);

        JobExecutionMetadata metadata = new JobExecutionMetadata(
                jobId,
                jobName,
                logId,
                MDC.get(TRACE_ID_KEY),
                TenantContextHolder.getTenantId()
        );

        LocalWatchdog.WatchdogSession session = LocalWatchdog.WatchdogSession.NOOP;
        boolean hasWatchdog = watchdog != null && timeout != null && !timeout.isZero() && !timeout.isNegative();

        // 6. 执行目标任务并确保终态严格清理
        try {
            if (properties != null && properties.isManagedPoolEnabled() && taskExecutor != null) {
                return executeInManagedPool(pjp, timeout, metadata);
            } else {
                if (hasWatchdog) {
                    session = watchdog.startWatch(Thread.currentThread(), timeout, metadata);
                }
                return pjp.proceed();
            }
        } catch (Throwable t) {
            if (session.isTimedOut() || (t instanceof InterruptedException && session.isTimedOut())) {
                log.warn("XXL-Job 任务 [{}] 本地硬超时 (阈值: {}) 并已被中断", jobName, timeout);
            }
            throw t;
        } finally {
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

    private Object executeInManagedPool(ProceedingJoinPoint pjp, Duration timeout, JobExecutionMetadata metadata) throws Throwable {
        XxlJobContext xxlJobContext = XxlJobContext.getXxlJobContext();
        CompletableFuture<Object> future = new CompletableFuture<>();

        taskExecutor.execute(() -> {
            Thread workerThread = Thread.currentThread();
            LocalWatchdog.WatchdogSession session = LocalWatchdog.WatchdogSession.NOOP;
            if (watchdog != null && timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                session = watchdog.startWatch(workerThread, timeout, metadata);
            }
            if (xxlJobContext != null) {
                XxlJobContext.setXxlJobContext(xxlJobContext);
            }
            try {
                Object result = pjp.proceed();
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

        try {
            return future.get();
        } catch (ExecutionException ee) {
            throw ee.getCause() != null ? ee.getCause() : ee;
        }
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
    protected String extractTraceId(String rawParam) {
        if (!StringUtils.hasText(rawParam)) {
            return null;
        }
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
        return null;
    }

    /**
     * 从任务参数中解析 userId / user
     */
    protected String extractUserId(String rawParam) {
        if (!StringUtils.hasText(rawParam)) {
            return null;
        }
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
        return null;
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
        if (args != null && args.length > 0) {
            for (Object arg : args) {
                if (arg != null && !(arg instanceof String)) {
                    try {
                        Method getTenantIdMethod = arg.getClass().getMethod("getTenantId");
                        Object res = getTenantIdMethod.invoke(arg);
                        if (res != null) {
                            return String.valueOf(res);
                        }
                    } catch (Exception ignored) {
                        // 忽略反射未找到
                    }
                }
            }
        }

        return null;
    }

}
