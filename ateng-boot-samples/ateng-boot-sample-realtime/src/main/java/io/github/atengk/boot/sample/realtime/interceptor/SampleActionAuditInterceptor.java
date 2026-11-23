package io.github.atengk.boot.sample.realtime.interceptor;

import io.github.atengk.boot.realtime.dispatcher.RealtimeActionInterceptor;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 示例上行 Action 责任链审计拦截器
 * <p>
 * 演示通过 Spring 组件自动装配接入 {@link RealtimeActionInterceptor} 责任链，
 * 在动作执行前后记录审计日志、统计拦截次数并验证全链路追踪上下文保持活跃。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Slf4j
@Order(100)
@Component
public class SampleActionAuditInterceptor implements RealtimeActionInterceptor {

    private final AtomicLong preHandleCount = new AtomicLong();
    private final AtomicLong afterCompletionCount = new AtomicLong();

    @Override
    public boolean preHandle(RealtimeSession session,
                             RealtimeMessage<?> message,
                             Object targetBean,
                             Method targetMethod,
                             Object[] args) {
        preHandleCount.incrementAndGet();
        log.info("[Sample-Interceptor] 前置放行动作: event={}, sessionKey={}, method={}, traceId={}",
                message.getEvent(), session.getSessionKey(), targetMethod.getName(), MDC.get("traceId"));
        return true;
    }

    @Override
    public void afterCompletion(RealtimeSession session,
                                RealtimeMessage<?> message,
                                Object targetBean,
                                Method targetMethod,
                                Object result,
                                Throwable ex) {
        afterCompletionCount.incrementAndGet();
        log.info("[Sample-Interceptor] 动作执行收尾: event={}, sessionKey={}, traceId={}, hasException={}",
                message.getEvent(), session.getSessionKey(), MDC.get("traceId"), ex != null);
    }

    /**
     * 获取累计触发的前置拦截次数（供集成验证观测）
     *
     * @return 前置拦截次数
     */
    public long getPreHandleCount() {
        return preHandleCount.get();
    }

    /**
     * 获取累计触发的后置收尾次数（供集成验证观测）
     *
     * @return 后置收尾次数
     */
    public long getAfterCompletionCount() {
        return afterCompletionCount.get();
    }
}
