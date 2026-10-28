package io.github.atengk.boot.statemachine.core;

/**
 * 状态跃迁前置条件断言卫语句
 *
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
@FunctionalInterface
public interface Condition<C> {

    /**
     * 评估前置条件是否满足
     *
     * @param context 业务上下文
     * @return true 表示条件满足允许流转，false 表示被拦截
     */
    boolean isSatisfied(C context);
}
