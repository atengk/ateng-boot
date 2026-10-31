package io.github.atengk.boot.job.xxl.aspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.enums.JobTenantMode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * XXL-Job 调度任务上下文与链路追踪切面
 *
 * <p>无感拦截由 {@link XxlJob} 或 {@link AtengXxlJob} 声明的调度任务执行方法，在任务执行前自动织入：
 * <ul>
 *     <li><b>MDC 链路追踪</b>：提取任务入参中的 traceId 或基于无锁算法生成 32 位唯一标识，写入 MDC；</li>
 *     <li><b>多租户隔离策略</b>：解析 {@link JobTenantMode}，按需标记 {@code IGNORE} 忽略租户过滤或从入参中提取 {@code tenantId}；</li>
 *     <li><b>操作人上下文</b>：自动绑定调度中心或系统身份标识（默认为 {@code SYSTEM}）。</li>
 * </ul>
 * 并在任务完成后无论成功或异常，严格执行现场恢复与现场清理，彻底杜绝线程池复用污染与上下文泄露。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class XxlJobContextAspect {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String DEFAULT_JOB_USER = "SYSTEM";

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

        // 5. 执行目标任务并确保终态严格清理
        try {
            return pjp.proceed();
        } finally {
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
