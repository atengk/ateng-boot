package io.github.atengk.boot.realtime.dispatcher;

import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.session.RealtimeSession;

import java.lang.reflect.Method;

/**
 * 上行 &#64;RealtimeAction 动作执行拦截器 SPI 扩展点
 * <p>
 * 支持按 Spring Ordered / &#64;Order 顺序编排责任链，在动作方法调用前后介入权限校验、
 * 审计日志、限流统计与异常埋点，并可在前置阶段中止方法执行。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-14
 */
public interface RealtimeActionInterceptor {

    /**
     * 目标 &#64;RealtimeAction 方法执行前的前置拦截回调
     *
     * @param session      当前客户端受管会话
     * @param message      原始上行实时消息
     * @param targetBean   目标控制器/处理器 Bean 实例
     * @param targetMethod 目标动作反射方法
     * @param args         已完成解析及校验的调用参数数组
     * @return {@code true} 表示继续放行后续拦截器与目标动作方法；{@code false} 表示立即中止执行
     */
    default boolean preHandle(RealtimeSession session,
                              RealtimeMessage<?> message,
                              Object targetBean,
                              Method targetMethod,
                              Object[] args) {
        return true;
    }

    /**
     * 目标 &#64;RealtimeAction 方法执行结束（正常返回或异常抛出/拦截）后的确定性收尾回调
     * 触发时机早于调度线程四维上下文（MDC / User / Tenant）的最终清理
     *
     * @param session      当前客户端受管会话
     * @param message      原始上行实时消息
     * @param targetBean   目标控制器/处理器 Bean 实例
     * @param targetMethod 目标动作反射方法
     * @param result       目标方法正常执行返回值（若被中止或抛出异常则为 {@code null}）
     * @param ex           执行期或前置拦截期捕获的真实异常原因（正常完成则为 {@code null}）
     */
    default void afterCompletion(RealtimeSession session,
                                 RealtimeMessage<?> message,
                                 Object targetBean,
                                 Method targetMethod,
                                 Object result,
                                 Throwable ex) {
    }
}
