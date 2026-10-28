package io.github.atengk.boot.statemachine.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Choice 动态条件分支路由分支项定义
 *
 * @param <S> 业务状态类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public class ChoiceBranch<S, C> {

    private final List<Condition<C>> conditions;
    private final S targetState;

    public ChoiceBranch(Condition<C> condition, S targetState) {
        this(condition != null ? Collections.singletonList(condition) : Collections.emptyList(), targetState);
    }

    public ChoiceBranch(List<Condition<C>> conditions, S targetState) {
        if (targetState == null) {
            throw new IllegalArgumentException("targetState must not be null");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("conditions must not be empty");
        }
        this.conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
        this.targetState = targetState;
    }

    public List<Condition<C>> getConditions() {
        return conditions;
    }

    public S getTargetState() {
        return targetState;
    }

    /**
     * 判断当前分支条件是否全部满足 (AND 语义)
     *
     * @param context 业务上下文
     * @return 全部满足返回 true，否则返回 false
     */
    public boolean isSatisfied(C context) {
        for (Condition<C> condition : conditions) {
            if (!condition.isSatisfied(context)) {
                return false;
            }
        }
        return true;
    }
}
