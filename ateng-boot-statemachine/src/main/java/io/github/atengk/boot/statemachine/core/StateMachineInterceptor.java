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
     * 判断当前切面拦截器是否支持指定的状态机标识
     * <p>默认返回 true，表示拦截所有状态机实例；强类型或专职拦截器可重写此方法实现多机台隔离</p>
     *
     * @param machineId 状态机唯一标识
     * @return true 代表支持挂载
     */
    default boolean supports(String machineId) {
        return true;
    }

    /**
     * 状态跃迁前置拦截钩子（携带状态机标识）
     *
     * @param machineId 状态机唯一标识
     * @param from      源状态
     * @param event     触发事件
     * @param context   业务上下文
     */
    default void beforeTransition(String machineId, S from, E event, C context) {
        beforeTransition(from, event, context);
    }

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
     * 状态跃迁成功后置拦截钩子（携带状态机标识与完整执行耗时）
     *
     * @param machineId 状态机唯一标识
     * @param from      源状态
     * @param to        跃迁后的目标状态
     * @param event     触发事件
     * @param context   业务上下文
     * @param elapsed   跃迁执行总耗时
     */
    default void afterTransition(String machineId, S from, S to, E event, C context, Duration elapsed) {
        afterTransition(from, to, event, context, elapsed);
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
     * 状态跃迁异常拦截钩子（携带状态机标识）
     *
     * @param machineId 状态机唯一标识
     * @param from      源状态
     * @param event     触发事件
     * @param context   业务上下文
     * @param ex        捕获的异常对象
     */
    default void onException(String machineId, S from, E event, C context, Exception ex) {
        onException(from, event, context, ex);
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
