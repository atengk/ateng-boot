package io.github.atengk.boot.statemachine.interceptor;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.statemachine.core.StateMachineInterceptor;
import io.github.atengk.boot.statemachine.properties.StateMachineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 状态机全链路日志审计拦截器
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LoggingStateMachineInterceptor implements StateMachineInterceptor<Object, Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(LoggingStateMachineInterceptor.class);
    private static final Pattern MOBILE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private final StateMachineProperties.Logging loggingProperties;

    public LoggingStateMachineInterceptor(StateMachineProperties.Logging loggingProperties) {
        this.loggingProperties = loggingProperties != null ? loggingProperties : new StateMachineProperties.Logging();
    }

    public StateMachineProperties.Logging getLoggingProperties() {
        return loggingProperties;
    }

    @Override
    public void beforeTransition(String machineId, Object from, Object event, Object context) {
        if (!loggingProperties.isEnabled()) {
            return;
        }
        String resolvedId = resolveMachineId(machineId);
        if (loggingProperties.isLogContext() && context != null) {
            log.info("[StateMachine] [{}] 开始执行状态跃迁: from={}, event={}, context={}",
                    resolvedId, from, event, formatContext(context));
        } else {
            log.info("[StateMachine] [{}] 开始执行状态跃迁: from={}, event={}", resolvedId, from, event);
        }
    }

    @Override
    public void beforeTransition(Object from, Object event, Object context) {
        beforeTransition(null, from, event, context);
    }

    @Override
    public void afterTransition(String machineId, Object from, Object to, Object event, Object context, Duration elapsed) {
        if (!loggingProperties.isEnabled()) {
            return;
        }
        String resolvedId = resolveMachineId(machineId);
        long millis = elapsed != null ? elapsed.toMillis() : 0;
        Duration threshold = loggingProperties.getSlowTransitionThreshold();
        if (threshold != null && !threshold.isNegative() && !threshold.isZero()
                && elapsed != null && elapsed.compareTo(threshold) >= 0) {
            log.warn("[StateMachine] [{}] 慢跃迁告警: from={} -> to={}, event={}, 耗时={}ms (阈值={}ms)",
                    resolvedId, from, to, event, millis, threshold.toMillis());
            return;
        }
        log.info("[StateMachine] [{}] 状态跃迁成功: from={} -> to={}, event={}, 耗时={}ms",
                resolvedId, from, to, event, millis);
    }

    @Override
    public void afterTransition(Object from, Object to, Object event, Object context, Duration elapsed) {
        afterTransition(null, from, to, event, context, elapsed);
    }

    @Override
    public void onException(String machineId, Object from, Object event, Object context, Exception ex) {
        if (!loggingProperties.isEnabled()) {
            return;
        }
        String resolvedId = resolveMachineId(machineId);
        String errorMsg = ex != null ? ex.getMessage() : "未知异常";
        log.warn("[StateMachine] [{}] 状态跃迁执行失败: from={}, event={}, 异常={}",
                resolvedId, from, event, errorMsg);
    }

    @Override
    public void onException(Object from, Object event, Object context, Exception ex) {
        onException(null, from, event, context, ex);
    }

    /**
     * 格式化业务上下文并执行脱敏保护
     * <p>字符序列支持手机号与邮箱掩码；POJO 上下文优先通过 {@link JsonUtil} 触发 {@code @FieldSensitive} 脱敏，
     * 并在文本层面兜底掩码未注解的手机号与邮箱</p>
     *
     * @param context 业务上下文
     * @return 脱敏后的上下文描述字符串
     */
    protected String formatContext(Object context) {
        if (context == null) {
            return "null";
        }
        if (context instanceof CharSequence) {
            return maskSensitivePatterns(context.toString());
        }
        if (context instanceof Number || context instanceof Boolean || context instanceof Character || context.getClass().isEnum()) {
            return String.valueOf(context);
        }
        String serialized;
        try {
            serialized = JsonUtil.toJsonString(context);
        } catch (Exception ex) {
            log.debug("[StateMachine] 上下文 JSON 序列化降级为 toString [type={}]: {}",
                    context.getClass().getSimpleName(), ex.getMessage());
            serialized = String.valueOf(context);
        }
        return maskSensitivePatterns(serialized);
    }

    private String maskSensitivePatterns(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String maskedMobile = replacePattern(raw, MOBILE_PATTERN, DesensitizeUtil::mobilePhone);
        return replacePattern(maskedMobile, EMAIL_PATTERN, DesensitizeUtil::email);
    }

    private String replacePattern(String input, Pattern pattern, java.util.function.UnaryOperator<String> masker) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String matched = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masker.apply(matched)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String resolveMachineId(String machineId) {
        return (machineId != null && !machineId.isBlank()) ? machineId : "UNKNOWN";
    }
}
