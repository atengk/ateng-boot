package io.github.atengk.boot.statemachine.builder;

import io.github.atengk.boot.statemachine.core.Action;

import java.util.function.Consumer;

/**
 * 状态节点生命周期动作流式构建器接口
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-11-01
 */
public interface StateLifecycleBuilder<S, E, C> {

    /**
     * 添加进入状态动作（支持链式添加多个，按声明顺序同步执行）
     *
     * @param action 执行动作
     * @return 当前状态生命周期构建器
     */
    StateLifecycleBuilder<S, E, C> onEntry(Action<S, E, C> action);

    /**
     * 便捷添加仅消费上下文的进入状态动作
     *
     * @param consumer 上下文消费函数
     * @return 当前状态生命周期构建器
     */
    default StateLifecycleBuilder<S, E, C> onEntry(Consumer<C> consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("Action consumer must not be null");
        }
        return onEntry((from, to, event, context) -> consumer.accept(context));
    }

    /**
     * 添加退出状态动作（支持链式添加多个，按声明顺序同步执行）
     *
     * @param action 执行动作
     * @return 当前状态生命周期构建器
     */
    StateLifecycleBuilder<S, E, C> onExit(Action<S, E, C> action);

    /**
     * 便捷添加仅消费上下文的退出状态动作
     *
     * @param consumer 上下文消费函数
     * @return 当前状态生命周期构建器
     */
    default StateLifecycleBuilder<S, E, C> onExit(Consumer<C> consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("Action consumer must not be null");
        }
        return onExit((from, to, event, context) -> consumer.accept(context));
    }

    /**
     * 返回所属状态机构建器以支持继续链式装配
     *
     * @return 状态机构建器实例
     */
    StateMachineBuilder<S, E, C> and();
}
