package io.github.atengk.boot.statemachine.interceptor;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.statemachine.core.StateMachineInterceptor;
import io.github.atengk.boot.statemachine.properties.StateMachineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 状态机全链路日志审计拦截器
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LoggingStateMachineInterceptor implements StateMachineInterceptor<Object, Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(LoggingStateMachineInterceptor.class);

    private final StateMachineProperties.Logging loggingProperties;

    public LoggingStateMachineInterceptor(StateMachineProperties.Logging loggingProperties) {
        this.loggingProperties = loggingProperties != null ? loggingProperties : new StateMachineProperties.Logging();
    }

    public StateMachineProperties.Logging getLoggingProperties() {
        return loggingProperties;
    }

    @Override
    public void beforeTransition(Object from, Object event, Object context) {
        if (!loggingProperties.isEnabled()) {
            return;
        }
        if (loggingProperties.isLogContext() && context != null) {
            log.info("[StateMachine] 开始执行状态跃迁: from={}, event={}, context={}", from, event, formatContext(context));
        } else {
            log.info("[StateMachine] 开始执行状态跃迁: from={}, event={}", from, event);
        }
    }

    @Override
    public void afterTransition(Object from, Object to, Object event, Object context, Duration elapsed) {
        if (!loggingProperties.isEnabled()) {
            return;
        }
        long millis = elapsed != null ? elapsed.toMillis() : 0;
        log.info("[StateMachine] 状态跃迁成功: from={} -> to={}, event={}, 耗时={}ms", from, to, event, millis);
    }

    @Override
    public void onException(Object from, Object event, Object context, Exception ex) {
        if (!loggingProperties.isEnabled()) {
            return;
        }
        String errorMsg = ex != null ? ex.getMessage() : "未知异常";
        log.warn("[StateMachine] 状态跃迁执行失败: from={}, event={}, 异常={}", from, event, errorMsg);
    }

    /**
     * 格式化业务上下文并执行脱敏保护
     *
     * @param context 业务上下文
     * @return 脱敏后的上下文描述字符串
     */
    protected String formatContext(Object context) {
        if (context == null) {
            return "null";
        }
        if (context instanceof CharSequence) {
            String str = context.toString();
            if (str.matches("^1[3-9]\\d{9}$")) {
                return DesensitizeUtil.mobilePhone(str);
            }
            if (str.contains("@") && str.contains(".")) {
                return DesensitizeUtil.email(str);
            }
            return str;
        }
        return String.valueOf(context);
    }
}
