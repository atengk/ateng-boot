package io.github.atengk.boot.statemachine.builder;

/**
 * 状态机构建器静态工厂
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class StateMachineBuilderFactory {

    private StateMachineBuilderFactory() {
    }

    /**
     * 创建一个全新的强类型状态机 Fluent DSL 构建器
     *
     * @param <S> 状态类型
     * @param <E> 事件类型
     * @param <C> 上下文类型
     * @return 状态机构建器实例
     */
    public static <S, E, C> StateMachineBuilder<S, E, C> create() {
        return new DefaultStateMachineBuilder<>();
    }
}
