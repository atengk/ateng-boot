package io.github.atengk.boot.statemachine.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Choice 动态条件分支路由跃迁规则实体
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public class ChoiceTransition<S, E, C> extends Transition<S, E, C> {

    private final List<ChoiceBranch<S, C>> branches;
    private final S defaultTargetState;

    public ChoiceTransition(S sourceState, E event, List<ChoiceBranch<S, C>> branches, S defaultTargetState, List<Action<S, E, C>> actions) {
        this(sourceState, event, branches, defaultTargetState, actions, Collections.emptyList(), false);
    }

    public ChoiceTransition(S sourceState, E event, List<ChoiceBranch<S, C>> branches, S defaultTargetState, List<Action<S, E, C>> actions, List<Action<S, E, C>> postCommitActions) {
        this(sourceState, event, branches, defaultTargetState, actions, postCommitActions, false);
    }

    public ChoiceTransition(S sourceState, E event, List<ChoiceBranch<S, C>> branches, S defaultTargetState, List<Action<S, E, C>> actions, List<Action<S, E, C>> postCommitActions, boolean idempotent) {
        super(sourceState, defaultTargetState, event, TransitionType.CHOICE, Collections.emptyList(), actions, postCommitActions, idempotent);
        if (sourceState == null) {
            throw new IllegalArgumentException("sourceState must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (branches == null || branches.isEmpty()) {
            throw new IllegalArgumentException("Choice branches must not be empty");
        }
        if (defaultTargetState == null) {
            throw new IllegalArgumentException("defaultTargetState must not be null");
        }
        this.branches = Collections.unmodifiableList(new ArrayList<>(branches));
        this.defaultTargetState = defaultTargetState;
    }

    public List<ChoiceBranch<S, C>> getBranches() {
        return branches;
    }

    public S getDefaultTargetState() {
        return defaultTargetState;
    }

    @Override
    public Set<S> getPossibleTargetStates() {
        Set<S> targets = new LinkedHashSet<>();
        for (ChoiceBranch<S, C> branch : branches) {
            targets.add(branch.getTargetState());
        }
        if (defaultTargetState != null) {
            targets.add(defaultTargetState);
        }
        return Collections.unmodifiableSet(targets);
    }

    @Override
    public boolean isSatisfied(C context) {
        // Choice 跃迁在匹配到 (sourceState, event) 时总是被接受，由动态分支或兜底默认分支决定最终目标状态
        return true;
    }

    @Override
    public S resolveTargetState(C context) {
        for (ChoiceBranch<S, C> branch : branches) {
            if (branch.isSatisfied(context)) {
                return branch.getTargetState();
            }
        }
        return defaultTargetState;
    }
}
