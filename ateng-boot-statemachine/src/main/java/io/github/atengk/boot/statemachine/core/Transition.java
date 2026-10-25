package io.github.atengk.boot.statemachine.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 状态机跃迁路由规则实体
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public class Transition<S, E, C> {

    private final S sourceState;
    private final S targetState;
    private final E event;
    private final TransitionType type;
    private final List<Condition<C>> conditions;
    private final List<Action<S, E, C>> actions;

    public Transition(S sourceState, S targetState, E event, TransitionType type, List<Condition<C>> conditions, List<Action<S, E, C>> actions) {
        if (sourceState == null) {
            throw new IllegalArgumentException("sourceState must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        this.sourceState = sourceState;
        this.targetState = targetState;
        this.event = event;
        this.type = type != null ? type : TransitionType.EXTERNAL;
        this.conditions = conditions != null ? Collections.unmodifiableList(new ArrayList<>(conditions)) : Collections.emptyList();
        this.actions = actions != null ? Collections.unmodifiableList(new ArrayList<>(actions)) : Collections.emptyList();
    }

    public S getSourceState() {
        return sourceState;
    }

    public S getTargetState() {
        return targetState;
    }

    public E getEvent() {
        return event;
    }

    public TransitionType getType() {
        return type;
    }

    public List<Condition<C>> getConditions() {
        return conditions;
    }

    public List<Action<S, E, C>> getActions() {
        return actions;
    }

    /**
     * 判断当前跃迁的前置条件是否全部满足
     *
     * @param context 业务上下文
     * @return 全部满足返回 true，任一不满足返回 false
     */
    public boolean isSatisfied(C context) {
        if (conditions.isEmpty()) {
            return true;
        }
        for (Condition<C> condition : conditions) {
            if (!condition.isSatisfied(context)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取跃迁目标状态
     *
     * @param context 业务上下文
     * @return 目标状态
     */
    public S resolveTargetState(C context) {
        if (type == TransitionType.INTERNAL) {
            return sourceState;
        }
        return targetState;
    }
}
