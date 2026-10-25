package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DefaultStateMachine.class);

    private final String machineId;
    private final Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap;
    private final List<StateMachineInterceptor<? super S, ? super E, ? super C>> interceptors;

    public DefaultStateMachine(String machineId,
                               Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap,
                               List<StateMachineInterceptor<? super S, ? super E, ? super C>> interceptors) {
        if (machineId == null || machineId.trim().isEmpty()) {
            throw new IllegalArgumentException("machineId must not be blank");
        }
        this.machineId = machineId;

        Map<S, Map<E, List<Transition<S, E, C>>>> deepImmutableMap = new LinkedHashMap<>();
        if (transitionMap != null) {
            for (Map.Entry<S, Map<E, List<Transition<S, E, C>>>> sourceEntry : transitionMap.entrySet()) {
                Map<E, List<Transition<S, E, C>>> eventMapCopy = new LinkedHashMap<>();
                if (sourceEntry.getValue() != null) {
                    for (Map.Entry<E, List<Transition<S, E, C>>> eventEntry : sourceEntry.getValue().entrySet()) {
                        List<Transition<S, E, C>> listCopy = eventEntry.getValue() != null
                                ? Collections.unmodifiableList(new ArrayList<>(eventEntry.getValue()))
                                : Collections.emptyList();
                        eventMapCopy.put(eventEntry.getKey(), listCopy);
                    }
                }
                deepImmutableMap.put(sourceEntry.getKey(), Collections.unmodifiableMap(eventMapCopy));
            }
        }
        this.transitionMap = Collections.unmodifiableMap(deepImmutableMap);
        this.interceptors = new CopyOnWriteArrayList<>(interceptors != null ? interceptors : Collections.emptyList());
    }

    public DefaultStateMachine(String machineId, Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap) {
        this(machineId, transitionMap, Collections.emptyList());
    }

    public List<StateMachineInterceptor<? super S, ? super E, ? super C>> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }

    public void addInterceptor(StateMachineInterceptor<? super S, ? super E, ? super C> interceptor) {
        if (interceptor != null && !this.interceptors.contains(interceptor)) {
            this.interceptors.add(interceptor);
        }
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

        long startTime = System.nanoTime();
        notifyInterceptorsBefore(sourceState, event, context);

        try {
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
                boolean satisfied;
                try {
                    satisfied = candidate.isSatisfied(context);
                } catch (Exception ex) {
                    throw new StateMachineException(
                            StateMachineResultCode.CONDITION_FAILED,
                            sourceState,
                            event,
                            String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句执行异常: %s", machineId, sourceState, event, ex.getMessage()),
                            ex
                    );
                }
                if (satisfied) {
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

            S targetState;
            try {
                targetState = matched.resolveTargetState(context);
            } catch (Exception ex) {
                throw new StateMachineException(
                        StateMachineResultCode.CONDITION_FAILED,
                        sourceState,
                        event,
                        String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 确定目标状态时异常: %s", machineId, sourceState, event, ex.getMessage()),
                        ex
                );
            }

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

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
            notifyInterceptorsAfter(sourceState, targetState, event, context, elapsed);

            return targetState;
        } catch (Exception ex) {
            notifyInterceptorsException(sourceState, event, context, ex);
            throw ex;
        }
    }

    @Override
    public TransitionResult<S> fireEventQuietly(S sourceState, E event, C context) {
        if (sourceState == null) {
            return TransitionResult.failure(null, event, "源状态不可为空");
        }
        if (event == null) {
            return TransitionResult.failure(sourceState, null, "触发事件不可为空");
        }

        long startTime = System.nanoTime();
        notifyInterceptorsBefore(sourceState, event, context);

        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;

        if (candidates == null || candidates.isEmpty()) {
            StateMachineException ex = new StateMachineException(
                    StateMachineResultCode.TRANSITION_NOT_FOUND,
                    sourceState,
                    event,
                    String.format("状态机 [%s] 未找到从状态 [%s] 响应事件 [%s] 的合法跃迁路由", machineId, sourceState, event)
            );
            notifyInterceptorsException(sourceState, event, context, ex);
            return TransitionResult.failure(
                    sourceState,
                    event,
                    String.format("状态机 [%s] 未找到从状态 [%s] 响应事件 [%s] 的合法跃迁路由", machineId, sourceState, event)
            );
        }

        Transition<S, E, C> matched = null;
        for (Transition<S, E, C> candidate : candidates) {
            boolean satisfied;
            try {
                satisfied = candidate.isSatisfied(context);
            } catch (Exception ex) {
                StateMachineException smEx = new StateMachineException(
                        StateMachineResultCode.CONDITION_FAILED,
                        sourceState,
                        event,
                        String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句执行异常: %s", machineId, sourceState, event, ex.getMessage()),
                        ex
                );
                notifyInterceptorsException(sourceState, event, context, smEx);
                return TransitionResult.failure(
                        sourceState,
                        event,
                        String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句执行异常: %s", machineId, sourceState, event, ex.getMessage()),
                        ex
                );
            }
            if (satisfied) {
                matched = candidate;
                break;
            }
        }

        if (matched == null) {
            StateMachineException ex = new StateMachineException(
                    StateMachineResultCode.CONDITION_FAILED,
                    sourceState,
                    event,
                    String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句校验不通过", machineId, sourceState, event)
            );
            notifyInterceptorsException(sourceState, event, context, ex);
            return TransitionResult.failure(
                    sourceState,
                    event,
                    String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句校验不通过", machineId, sourceState, event)
            );
        }

        S targetState;
        try {
            targetState = matched.resolveTargetState(context);
        } catch (Exception ex) {
            StateMachineException smEx = new StateMachineException(
                    StateMachineResultCode.CONDITION_FAILED,
                    sourceState,
                    event,
                    String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 确定目标状态时异常: %s", machineId, sourceState, event, ex.getMessage()),
                    ex
            );
            notifyInterceptorsException(sourceState, event, context, smEx);
            return TransitionResult.failure(
                    sourceState,
                    event,
                    String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 确定目标状态时异常: %s", machineId, sourceState, event, ex.getMessage()),
                    ex
            );
        }

        for (Action<S, E, C> action : matched.getActions()) {
            try {
                action.execute(sourceState, targetState, event, context);
            } catch (Exception ex) {
                StateMachineException smEx = new StateMachineException(
                        StateMachineResultCode.ACTION_EXECUTION_FAILED,
                        sourceState,
                        targetState,
                        event,
                        String.format("状态机 [%s] 跃迁动作执行失败 [%s -> %s on %s]: %s", machineId, sourceState, targetState, event, ex.getMessage()),
                        ex
                );
                notifyInterceptorsException(sourceState, event, context, smEx);
                return TransitionResult.failure(
                        sourceState,
                        event,
                        String.format("状态机 [%s] 跃迁动作执行失败 [%s -> %s on %s]: %s", machineId, sourceState, targetState, event, ex.getMessage()),
                        ex
                );
            }
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
        notifyInterceptorsAfter(sourceState, targetState, event, context, elapsed);

        return TransitionResult.success(sourceState, targetState, event);
    }

    private void notifyInterceptorsBefore(S sourceState, E event, C context) {
        for (StateMachineInterceptor<? super S, ? super E, ? super C> interceptor : interceptors) {
            try {
                interceptor.beforeTransition(sourceState, event, context);
            } catch (Exception ex) {
                log.warn("状态机 [{}] 拦截器 [{}] 前置通知执行异常: {}", machineId, interceptor.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        }
    }

    private void notifyInterceptorsAfter(S sourceState, S targetState, E event, C context, Duration elapsed) {
        for (StateMachineInterceptor<? super S, ? super E, ? super C> interceptor : interceptors) {
            try {
                interceptor.afterTransition(sourceState, targetState, event, context, elapsed);
            } catch (Exception ex) {
                log.warn("状态机 [{}] 拦截器 [{}] 后置通知执行异常: {}", machineId, interceptor.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        }
    }

    private void notifyInterceptorsException(S sourceState, E event, C context, Exception ex) {
        for (StateMachineInterceptor<? super S, ? super E, ? super C> interceptor : interceptors) {
            try {
                interceptor.onException(sourceState, event, context, ex);
            } catch (Exception interceptorEx) {
                log.warn("状态机 [{}] 拦截器 [{}] 异常回调处理失败: {}", machineId, interceptor.getClass().getSimpleName(), interceptorEx.getMessage(), interceptorEx);
            }
        }
    }

    private Set<String> collectDiagramTransitions(String indent) {
        Set<String> renderedLines = new LinkedHashSet<>();
        for (Map.Entry<S, Map<E, List<Transition<S, E, C>>>> sourceEntry : transitionMap.entrySet()) {
            S from = sourceEntry.getKey();
            for (Map.Entry<E, List<Transition<S, E, C>>> eventEntry : sourceEntry.getValue().entrySet()) {
                E event = eventEntry.getKey();
                for (Transition<S, E, C> transition : eventEntry.getValue()) {
                    S to = transition.getTargetState();
                    if (transition.getType() == TransitionType.INTERNAL) {
                        renderedLines.add(String.format("%s%s --> %s : %s", indent, from, from, event));
                    } else if (transition instanceof ChoiceTransition) {
                        ChoiceTransition<S, E, C> choice = (ChoiceTransition<S, E, C>) transition;
                        for (ChoiceBranch<S, C> branch : choice.getBranches()) {
                            renderedLines.add(String.format("%s%s --> %s : %s [choice]", indent, from, branch.getTargetState(), event));
                        }
                        renderedLines.add(String.format("%s%s --> %s : %s [default]", indent, from, choice.getDefaultTargetState(), event));
                    } else {
                        renderedLines.add(String.format("%s%s --> %s : %s", indent, from, to, event));
                    }
                }
            }
        }
        return renderedLines;
    }

    @Override
    public String generateMermaid() {
        StringBuilder sb = new StringBuilder();
        sb.append("stateDiagram-v2\n");
        for (String line : collectDiagramTransitions("    ")) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public String generatePlantUML() {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        for (String line : collectDiagramTransitions("")) {
            sb.append(line).append("\n");
        }
        sb.append("@enduml");
        return sb.toString().trim();
    }
}
