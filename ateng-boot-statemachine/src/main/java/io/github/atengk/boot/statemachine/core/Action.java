package io.github.atengk.boot.statemachine.core;

/**
 * 状态跃迁动作执行器
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
@FunctionalInterface
public interface Action<S, E, C> {

    /**
     * 执行跃迁伴随业务动作
     *
     * @param from    源状态
     * @param to      目标状态
     * @param event   触发事件
     * @param context 业务上下文
     * @throws Exception 动作执行异常
     */
    void execute(S from, S to, E event, C context) throws Exception;
}
