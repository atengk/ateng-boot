package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;

import java.util.*;

/**
 * 默认纯内存高性能无状态业务状态机实现
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public class DefaultStateMachine<S, E, C> implements StateMachine<S, E, C> {

    private final String machineId;
    private final Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap;

    public DefaultStateMachine(String machineId, Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap) {
        if (machineId == null || machineId.trim().isEmpty()) {
            throw new IllegalArgumentException("machineId must not be blank");
        }
        this.machineId = machineId;
        this.transitionMap = Collections.unmodifiableMap(transitionMap != null ? transitionMap : Collections.emptyMap());
    }

    @Override
    public String getMachineId() {
        return machineId;
    }

    @Override
    public S fireEvent(S sourceState, E event, C context) {
        if (sourceState == null) {
            throw new IllegalArgumentException("sourceState must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }

        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;

        if (candidates == null || candidates.isEmpty()) {
            throw new StateMachineException(
                    StateMachineResultCode.TRANSITION_NOT_FOUND,
                    sourceState,
                    event,
                    String.format("状态机 [%s] 未找到从状态 [%s] 响应事件 [%s] 的合法跃迁路由", machineId, sourceState, event)
            );
        }

        Transition<S, E, C> matched = null;
        for (Transition<S, E, C> candidate : candidates) {
            if (candidate.isSatisfied(context)) {
                matched = candidate;
                break;
            }
        }

        if (matched == null) {
            throw new StateMachineException(
                    StateMachineResultCode.CONDITION_FAILED,
                    sourceState,
                    event,
                    String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句校验不通过", machineId, sourceState, event)
            );
        }

        S targetState = matched.resolveTargetState(context);

        // 有序执行动作链
        for (Action<S, E, C> action : matched.getActions()) {
            try {
                action.execute(sourceState, targetState, event, context);
            } catch (Exception ex) {
                throw new StateMachineException(
                        StateMachineResultCode.ACTION_EXECUTION_FAILED,
                        sourceState,
                        targetState,
                        event,
                        String.format("状态机 [%s] 跃迁动作执行失败 [%s -> %s on %s]: %s", machineId, sourceState, targetState, event, ex.getMessage()),
                        ex
                );
            }
        }

        return targetState;
    }

    @Override
    public TransitionResult<S> fireEventQuietly(S sourceState, E event, C context) {
        if (sourceState == null) {
            return TransitionResult.failure(null, event, "源状态不可为空");
        }
        if (event == null) {
            return TransitionResult.failure(sourceState, null, "触发事件不可为空");
        }

        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;

        if (candidates == null || candidates.isEmpty()) {
            return TransitionResult.failure(
                    sourceState,
                    event,
                    String.format("状态机 [%s] 未找到从状态 [%s] 响应事件 [%s] 的合法跃迁路由", machineId, sourceState, event)
            );
        }

        Transition<S, E, C> matched = null;
        for (Transition<S, E, C> candidate : candidates) {
            if (candidate.isSatisfied(context)) {
                matched = candidate;
                break;
            }
        }

        if (matched == null) {
            return TransitionResult.failure(
                    sourceState,
                    event,
                    String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句校验不通过", machineId, sourceState, event)
            );
        }

        S targetState = matched.resolveTargetState(context);

        for (Action<S, E, C> action : matched.getActions()) {
            try {
                action.execute(sourceState, targetState, event, context);
            } catch (Exception ex) {
                return TransitionResult.failure(
                        sourceState,
                        event,
                        String.format("状态机 [%s] 跃迁动作执行失败 [%s -> %s on %s]: %s", machineId, sourceState, targetState, event, ex.getMessage()),
                        ex
                );
            }
        }

        return TransitionResult.success(sourceState, targetState, event);
    }

    @Override
    public String generateMermaid() {
        StringBuilder sb = new StringBuilder();
        sb.append("stateDiagram-v2\n");

        Set<String> renderedLines = new LinkedHashSet<>();
        for (Map.Entry<S, Map<E, List<Transition<S, E, C>>>> sourceEntry : transitionMap.entrySet()) {
            S from = sourceEntry.getKey();
            for (Map.Entry<E, List<Transition<S, E, C>>> eventEntry : sourceEntry.getValue().entrySet()) {
                E event = eventEntry.getKey();
                for (Transition<S, E, C> transition : eventEntry.getValue()) {
                    S to = transition.getTargetState();
                    if (transition.getType() == TransitionType.INTERNAL) {
                        renderedLines.add(String.format("    %s --> %s : %s", from, from, event));
                    } else {
                        renderedLines.add(String.format("    %s --> %s : %s", from, to, event));
                    }
                }
            }
        }

        for (String line : renderedLines) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public String generatePlantUML() {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");

        Set<String> renderedLines = new LinkedHashSet<>();
        for (Map.Entry<S, Map<E, List<Transition<S, E, C>>>> sourceEntry : transitionMap.entrySet()) {
            S from = sourceEntry.getKey();
            for (Map.Entry<E, List<Transition<S, E, C>>> eventEntry : sourceEntry.getValue().entrySet()) {
                E event = eventEntry.getKey();
                for (Transition<S, E, C> transition : eventEntry.getValue()) {
                    S to = transition.getTargetState();
                    if (transition.getType() == TransitionType.INTERNAL) {
                        renderedLines.add(String.format("%s --> %s : %s", from, from, event));
                    } else {
                        renderedLines.add(String.format("%s --> %s : %s", from, to, event));
                    }
                }
            }
        }

        for (String line : renderedLines) {
            sb.append(line).append("\n");
        }
        sb.append("@enduml");
        return sb.toString().trim();
    }
}
