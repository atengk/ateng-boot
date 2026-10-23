package io.github.atengk.boot.notification.aspect;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.notification.alert.AlertContext;
import io.github.atengk.boot.notification.alert.AlertStormSuppressor;
import io.github.atengk.boot.notification.annotation.NotifyAlert;
import io.github.atengk.boot.notification.properties.NotificationAlertProperties;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 声明式异常告警切面
 * 负责在目标方法抛出未受控异常时，安全采集 MDC TraceId、用户上下文、脱敏入参快照与核心调用栈，并委托防风暴聚合器推送告警
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Aspect
@Slf4j
public class NotifyAlertAspect {

    private final AlertStormSuppressor stormSuppressor;
    private final NotificationAlertProperties properties;

    public NotifyAlertAspect(AlertStormSuppressor stormSuppressor, NotificationAlertProperties properties) {
        this.stormSuppressor = stormSuppressor;
        this.properties = properties != null ? properties : new NotificationAlertProperties();
    }

    @Pointcut("@annotation(io.github.atengk.boot.notification.annotation.NotifyAlert) || @within(io.github.atengk.boot.notification.annotation.NotifyAlert)")
    public void notifyAlertPointcut() {
    }

    @AfterThrowing(pointcut = "notifyAlertPointcut()", throwing = "ex")
    public void afterThrowing(JoinPoint joinPoint, Throwable ex) {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();

            NotifyAlert notifyAlert = resolveAnnotation(method, joinPoint.getTarget().getClass());
            if (notifyAlert == null) {
                return;
            }

            if (!shouldAlert(notifyAlert, ex)) {
                return;
            }

            AlertContext alertContext = buildAlertContext(joinPoint, method, notifyAlert, ex);
            stormSuppressor.processAlert(alertContext);
        } catch (Throwable aspectEx) {
            log.error("NotifyAlertAspect 切面处理告警异常: {}", aspectEx.getMessage(), aspectEx);
        }
    }

    private NotifyAlert resolveAnnotation(Method method, Class<?> targetClass) {
        NotifyAlert alert = method.getAnnotation(NotifyAlert.class);
        if (alert != null) {
            return alert;
        }
        return targetClass.getAnnotation(NotifyAlert.class);
    }

    private boolean shouldAlert(NotifyAlert alert, Throwable ex) {
        if (alert.ignoreExceptions() != null) {
            for (Class<? extends Throwable> ignored : alert.ignoreExceptions()) {
                if (ignored.isInstance(ex)) {
                    return false;
                }
            }
        }
        if (alert.includeExceptions() != null && alert.includeExceptions().length > 0) {
            for (Class<? extends Throwable> included : alert.includeExceptions()) {
                if (included.isInstance(ex)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private AlertContext buildAlertContext(JoinPoint joinPoint, Method method, NotifyAlert alert, Throwable ex) {
        String fingerprint = AlertStormSuppressor.computeFingerprint(method, ex);
        Throwable rootCause = AlertStormSuppressor.findRootCause(ex);
        int lineNumber = AlertStormSuppressor.findFirstBusinessLineNumber(ex, method);

        Duration suppressWindow = (alert.suppressWindow() > 0)
                ? Duration.ofSeconds(alert.suppressWindow())
                : properties.getSuppressWindow();

        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = MDC.get("trace_id");
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = IdUtil.simpleUUID();
        }

        String userId = UserContextHolder.get();

        String desensitizedArgs = null;
        if (alert.includeArgs() && properties.isIncludeArgs()) {
            desensitizedArgs = extractDesensitizedArgs(joinPoint, method);
        }

        String topStackFrames = extractTopStackFrames(ex);

        return AlertContext.builder()
                .fingerprint(fingerprint)
                .channel(alert.channel())
                .title(alert.title())
                .methodSignature(method.getDeclaringClass().getName() + "#" + method.getName())
                .lineNumber(lineNumber)
                .exceptionClassName(ex.getClass().getName())
                .exceptionMessage(ex.getMessage())
                .rootCauseClassName(rootCause != null ? rootCause.getClass().getName() : ex.getClass().getName())
                .rootCauseMessage(rootCause != null ? rootCause.getMessage() : ex.getMessage())
                .traceId(traceId)
                .userId(userId)
                .desensitizedArgs(desensitizedArgs)
                .topStackFrames(topStackFrames)
                .suppressWindow(suppressWindow)
                .occurrenceTime(LocalDateTime.now())
                .build();
    }

    private String extractDesensitizedArgs(JoinPoint joinPoint, Method method) {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return null;
        }

        Parameter[] parameters = method.getParameters();
        Map<String, Object> map = new LinkedHashMap<>();

        for (int i = 0; i < args.length; i++) {
            String paramName = (i < parameters.length) ? parameters[i].getName() : "arg" + i;
            Object arg = args[i];

            if (isExcludedArgType(arg)) {
                continue;
            }

            if (isSensitiveParam(paramName)) {
                map.put(paramName, "******");
            } else if (arg == null) {
                map.put(paramName, null);
            } else {
                map.put(paramName, sanitizeArgValue(arg));
            }
        }

        try {
            String json = JsonUtil.toJsonString(map);
            return maskSensitiveJson(json);
        } catch (Throwable ex) {
            return maskSensitiveJson(map.toString());
        }
    }

    private String maskSensitiveJson(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        java.util.List<String> keys = properties != null ? properties.getDesensitizeKeys() : null;
        if (keys == null || keys.isEmpty()) {
            keys = java.util.List.of("password", "secret", "token", "credential", "pwd", "passwd", "idcard", "mobile", "auth");
        }
        String result = input;
        for (String key : keys) {
            String regex = "(?i)(\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*)(\"[^\"]*\"|[^,\\s\\]\\}]+)";
            result = result.replaceAll(regex, "$1\"******\"");
        }
        return result;
    }

    private boolean isExcludedArgType(Object arg) {
        if (arg == null) {
            return false;
        }
        if (arg instanceof InputStream || arg instanceof OutputStream) {
            return true;
        }
        String className = arg.getClass().getName();
        return className.contains("ServletRequest") || className.contains("ServletResponse")
                || className.contains("MultipartFile") || className.contains("BindingResult");
    }

    private boolean isSensitiveParam(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("password") || lower.contains("secret") || lower.contains("token")
                || lower.contains("credential") || lower.contains("passwd") || lower.contains("pwd")
                || lower.contains("key") || lower.contains("auth");
    }

    private Object sanitizeArgValue(Object arg) {
        if (arg instanceof CharSequence str) {
            if (str.length() > 500) {
                return str.subSequence(0, 500) + "...(truncated)";
            }
        }
        return arg;
    }

    private String extractTopStackFrames(Throwable ex) {
        StackTraceElement[] elements = ex.getStackTrace();
        if (elements == null || elements.length == 0) {
            return "";
        }
        int limit = Math.min(10, elements.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            sb.append("  at ").append(elements[i].toString()).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
