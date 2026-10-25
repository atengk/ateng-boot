package io.github.atengk.boot.statemachine.core;

import java.time.Duration;

/**
 * 状态机三阶段生命周期切面拦截器 SPI
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public interface StateMachineInterceptor<S, E, C> {

    /**
     * 状态跃迁前置拦截钩子
     *
     * @param from    源状态
     * @param event   触发事件
     * @param context 业务上下文
     */
    default void beforeTransition(S from, E event, C context) {
    }

    /**
     * 状态跃迁成功后置拦截钩子（携带完整执行耗时）
     *
     * @param from    源状态
     * @param to      跃迁后的目标状态
     * @param event   触发事件
     * @param context 业务上下文
     * @param elapsed 跃迁执行总耗时
     */
    default void afterTransition(S from, S to, E event, C context, Duration elapsed) {
    }

    /**
     * 状态跃迁异常拦截钩子
     *
     * @param from    源状态
     * @param event   触发事件
     * @param context 业务上下文
     * @param ex      捕获的异常对象
     */
    default void onException(S from, E event, C context, Exception ex) {
    }
}
