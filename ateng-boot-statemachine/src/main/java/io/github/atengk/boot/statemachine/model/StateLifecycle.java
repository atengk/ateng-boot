package io.github.atengk.boot.statemachine.model;

import io.github.atengk.boot.statemachine.core.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 状态节点进入与退出生命周期动作聚合模型
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-11-01
 */
public class StateLifecycle<S, E, C> {

    private final S state;
    private final List<Action<S, E, C>> entryActions;
    private final List<Action<S, E, C>> exitActions;

    public StateLifecycle(S state, List<Action<S, E, C>> entryActions, List<Action<S, E, C>> exitActions) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        this.state = state;
        this.entryActions = entryActions != null ? Collections.unmodifiableList(new ArrayList<>(entryActions)) : Collections.emptyList();
        this.exitActions = exitActions != null ? Collections.unmodifiableList(new ArrayList<>(exitActions)) : Collections.emptyList();
    }

    public S getState() {
        return state;
    }

    public List<Action<S, E, C>> getEntryActions() {
        return entryActions;
    }

    public List<Action<S, E, C>> getExitActions() {
        return exitActions;
    }

    public boolean hasEntryActions() {
        return !entryActions.isEmpty();
    }

    public boolean hasExitActions() {
        return !exitActions.isEmpty();
    }
}
