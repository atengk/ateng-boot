package io.github.atengk.boot.statemachine.builder;

import io.github.atengk.boot.statemachine.core.StateMachine;

/**
 * 强类型状态机 Fluent DSL 构建器顶层契约
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public interface StateMachineBuilder<S, E, C> {

    /**
     * 声明标准外部状态跃迁 (S1 -> S2)
     *
     * @return 外部跃迁装配入口
     */
    ExternalTransitionBuilder.From<S, E, C> externalTransition();

    /**
     * 声明内部自状态跃迁 (S1 -> S1，状态保持不变)
     *
     * @return 内部自跃迁装配入口
     */
    InternalTransitionBuilder.From<S, E, C> internalTransition();

    /**
     * 声明多源汇聚状态跃迁 ([S1, S2, ...] -> S3)
     *
     * @return 多源汇聚跃迁装配入口
     */
    MultiFromTransitionBuilder.From<S, E, C> multiFromTransition();

    /**
     * 声明动态条件分支路由跃迁 (Choice: S1 -> S2 if C1 else S3)
     *
     * @return 条件分支路由跃迁装配入口
     */
    ChoiceTransitionBuilder.From<S, E, C> choiceTransition();

    /**
     * 挂载状态机切面拦截器
     *
     * @param interceptor 拦截器
     * @return 当前构建器
     */
    StateMachineBuilder<S, E, C> interceptor(io.github.atengk.boot.statemachine.core.StateMachineInterceptor<? super S, ? super E, ? super C> interceptor);

    /**
     * 构建只读不可变状态机实例
     *
     * @param machineId 状态机全局唯一标识
     * @return 状态机实例
     */
    StateMachine<S, E, C> build(String machineId);
}
