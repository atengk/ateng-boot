package io.github.atengk.boot.core.aspect;

import io.github.atengk.boot.core.annotation.SlowAlert;
import io.github.atengk.boot.core.properties.SlowAlertProperties;
import io.github.atengk.boot.core.util.JsonUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 方法级慢调用告警切面
 * 拦截标注有 @SlowAlert 注解的方法或类，精准度量执行耗时并在超过阈值时输出结构化 WARN 告警日志
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Aspect
public class SlowAlertAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(SlowAlertAspect.class);

    /**
     * 敏感字段键名掩码正则表达式（匹配 JSON 键值对中的常见敏感凭据）
     */
    private static final Pattern SENSITIVE_PARAM_PATTERN = Pattern.compile(
            "(?i)\"(password|pwd|token|secret|key|accessToken|refreshToken|clientSecret|credential|idCard|mobile)\"\\s*:\\s*\"[^\"]*\""
    );

    private static final String TRUNCATED_SUFFIX = " ... [TRUNCATED]";

    private final SlowAlertProperties properties;

    public SlowAlertAspect() {
        this(new SlowAlertProperties());
    }

    public SlowAlertAspect(SlowAlertProperties properties) {
        this.properties = properties != null ? properties : new SlowAlertProperties();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Around("@annotation(io.github.atengk.boot.core.annotation.SlowAlert) || @within(io.github.atengk.boot.core.annotation.SlowAlert)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() != null ? joinPoint.getTarget().getClass() : signature.getDeclaringType();

        SlowAlert slowAlert = AnnotatedElementUtils.findMergedAnnotation(method, SlowAlert.class);
        if (slowAlert == null) {
            slowAlert = AnnotatedElementUtils.findMergedAnnotation(targetClass, SlowAlert.class);
        }

        long threshold = resolveThreshold(slowAlert);
        long startNano = System.nanoTime();

        try {
            return joinPoint.proceed();
        } finally {
            long cost = (System.nanoTime() - startNano) / 1_000_000L;
            if (cost >= threshold) {
                handleSlowCall(joinPoint, method, slowAlert, cost, threshold);
            }
        }
    }

    /**
     * 计算当前方法调用的有效慢调用耗时阈值
     */
    private long resolveThreshold(SlowAlert slowAlert) {
        if (slowAlert != null && slowAlert.threshold() > 0) {
            return slowAlert.threshold();
        }
        Duration defaultDuration = properties.getDefaultThreshold();
        return defaultDuration != null ? Math.max(0, defaultDuration.toMillis()) : 500L;
    }

    /**
     * 输出慢调用警告日志并记录脱敏入参快照
     */
    private void handleSlowCall(ProceedingJoinPoint joinPoint, Method method, SlowAlert slowAlert, long cost, long threshold) {
        String methodName = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        String description = (slowAlert != null && !slowAlert.value().isBlank()) ? " (" + slowAlert.value() + ")" : "";

        boolean includeArgs = slowAlert != null ? slowAlert.includeArgs() : properties.isIncludeArgs();
        int maxArgsLength = slowAlert != null && slowAlert.maxArgsLength() > 0 ? slowAlert.maxArgsLength() : properties.getMaxArgsLength();

        if (includeArgs) {
            String argsSnapshot = formatArgsSnapshot(joinPoint, maxArgsLength);
            log.warn("[SLOW-ALERT] 方法调用超时告警{}：[{}] 执行耗时 {} ms (阈值: {} ms), 入参: {}",
                    description, methodName, cost, threshold, argsSnapshot);
        } else {
            log.warn("[SLOW-ALERT] 方法调用超时告警{}：[{}] 执行耗时 {} ms (阈值: {} ms)",
                    description, methodName, cost, threshold);
        }
    }

    /**
     * 格式化入参快照并进行脱敏与超长截断防 OOM
     */
    private String formatArgsSnapshot(ProceedingJoinPoint joinPoint, int maxArgsLength) {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return "{}";
        }
        try {
            Object toSerialize = args;
            if (joinPoint.getSignature() instanceof MethodSignature methodSignature) {
                String[] paramNames = methodSignature.getParameterNames();
                if (paramNames != null && paramNames.length == args.length) {
                    java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < paramNames.length; i++) {
                        map.put(paramNames[i], args[i]);
                    }
                    toSerialize = map;
                }
            }

            String json = JsonUtil.toJsonString(toSerialize);
            if (json == null) {
                json = "{}";
            }
            // 敏感凭据正则掩码
            String masked = SENSITIVE_PARAM_PATTERN.matcher(json).replaceAll("\"$1\":\"******\"");
            if (masked.length() > maxArgsLength) {
                return masked.substring(0, maxArgsLength) + TRUNCATED_SUFFIX;
            }
            return masked;
        } catch (Throwable ex) {
            // 防御参数中不可序列化或循环引用的对象
            return "[UNSERIALIZABLE_ARGS]";
        }
    }
}
