package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.BatchOptions;
import io.github.atengk.boot.statemachine.model.BatchTransitionResult;
import io.github.atengk.boot.statemachine.model.StateLifecycle;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.springframework.util.ClassUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;

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
    private static final boolean SPRING_TX_PRESENT = ClassUtils.isPresent(
            "org.springframework.transaction.support.TransactionSynchronizationManager",
            DefaultStateMachine.class.getClassLoader()
    );

    private final String machineId;
    private final Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap;
    private final List<StateMachineInterceptor<? super S, ? super E, ? super C>> interceptors;
    private final Map<S, Set<E>> idempotentTargets;
    private final Map<S, StateLifecycle<S, E, C>> stateLifecycleMap;

    public DefaultStateMachine(String machineId,
                               Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap,
                               List<StateMachineInterceptor<? super S, ? super E, ? super C>> interceptors,
                               Map<S, StateLifecycle<S, E, C>> stateLifecycleMap) {
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

        Map<S, Set<E>> idempMap = new LinkedHashMap<>();
        for (Map<E, List<Transition<S, E, C>>> eventMap : deepImmutableMap.values()) {
            for (List<Transition<S, E, C>> transitions : eventMap.values()) {
                for (Transition<S, E, C> transition : transitions) {
                    if (transition.isIdempotent()) {
                        if (transition instanceof ChoiceTransition) {
                            ChoiceTransition<S, E, C> choice = (ChoiceTransition<S, E, C>) transition;
                            for (ChoiceBranch<S, C> branch : choice.getBranches()) {
                                idempMap.computeIfAbsent(branch.getTargetState(), k -> new LinkedHashSet<>()).add(transition.getEvent());
                            }
                            idempMap.computeIfAbsent(choice.getDefaultTargetState(), k -> new LinkedHashSet<>()).add(transition.getEvent());
                        } else {
                            idempMap.computeIfAbsent(transition.getTargetState(), k -> new LinkedHashSet<>()).add(transition.getEvent());
                        }
                    }
                }
            }
        }
        Map<S, Set<E>> immutableIdempMap = new LinkedHashMap<>();
        for (Map.Entry<S, Set<E>> entry : idempMap.entrySet()) {
            immutableIdempMap.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
        this.idempotentTargets = Collections.unmodifiableMap(immutableIdempMap);
        this.stateLifecycleMap = stateLifecycleMap != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(stateLifecycleMap))
                : Collections.emptyMap();
    }

    public DefaultStateMachine(String machineId,
                               Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap,
                               List<StateMachineInterceptor<? super S, ? super E, ? super C>> interceptors) {
        this(machineId, transitionMap, interceptors, Collections.emptyMap());
    }

    public DefaultStateMachine(String machineId, Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap) {
        this(machineId, transitionMap, Collections.emptyList(), Collections.emptyMap());
    }

    public Map<S, StateLifecycle<S, E, C>> getStateLifecycleMap() {
        return stateLifecycleMap;
    }

    public StateLifecycle<S, E, C> getStateLifecycle(S state) {
        return stateLifecycleMap.get(state);
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
                if (isIdempotentHit(sourceState, event)) {
                    log.info("状态机 [{}] 幂等命中 [IDEMPOTENT_HIT]: 当前已处于目标状态 [{}], 重复事件 [{}] 放行，跳过动作执行",
                            machineId, sourceState, event);
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
                    notifyInterceptorsAfter(sourceState, sourceState, event, context, elapsed);
                    return sourceState;
                }
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

            boolean isInternal = matched.getType() == TransitionType.INTERNAL
                    || (matched.getType() == TransitionType.CHOICE && Objects.equals(sourceState, targetState));

            if (!isInternal) {
                executeExitActions(sourceState, targetState, event, context);
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

            if (!isInternal) {
                executeEntryActions(sourceState, targetState, event, context);
            }

            // 调度事务提交后动作（若处于 Spring 事务中则注册 afterCommit，否则就地同步执行）
            schedulePostCommitActions(matched.getPostCommitActions(), sourceState, targetState, event, context);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
            notifyInterceptorsAfter(sourceState, targetState, event, context, elapsed);

            return targetState;
        } catch (Exception ex) {
            notifyInterceptorsException(sourceState, event, context, ex);
            throw ex;
        }
    }

    @Override
    public <T> BatchTransitionResult<S, T> fireEventBatch(
            List<T> entities,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            C context,
            BatchOptions options
    ) {
        if (getter == null || setter == null) {
            throw new IllegalArgumentException("Getter and setter must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("Event must not be null");
        }
        BatchOptions opts = options != null ? options : BatchOptions.defaults();
        if (CollectionUtil.isEmpty(entities)) {
            return BatchTransitionResult.empty();
        }

        List<List<T>> slices = BatchSliceUtil.slice(entities, opts.getBatchSize());
        List<T> successEntities = new ArrayList<>(entities.size());
        Map<T, StateMachineException> failures = new LinkedHashMap<>();

        for (List<T> slice : slices) {
            for (T entity : slice) {
                if (entity == null) {
                    continue;
                }
                S sourceState = getter.apply(entity);
                try {
                    S targetState = fireEvent(sourceState, event, context);
                    setter.accept(entity, targetState);
                    successEntities.add(entity);
                } catch (StateMachineException smEx) {
                    if (opts.isFailFast()) {
                        throw new StateMachineException(
                                StateMachineResultCode.BATCH_TRANSITION_FAILED,
                                sourceState,
                                smEx.getTargetState(),
                                event,
                                String.format("状态机 [%s] 大批量流转触发 Fail-Fast 熔断中断 [entity=%s]: %s",
                                        machineId, entity, smEx.getMessage()),
                                smEx
                        );
                    }
                    failures.put(entity, smEx);
                } catch (Exception ex) {
                    StateMachineException smEx = new StateMachineException(
                            StateMachineResultCode.ACTION_EXECUTION_FAILED,
                            sourceState,
                            event,
                            String.format("状态机 [%s] 实体批量跃迁异常 [entity=%s]: %s",
                                    machineId, entity, ex.getMessage()),
                            ex
                    );
                    if (opts.isFailFast()) {
                        throw new StateMachineException(
                                StateMachineResultCode.BATCH_TRANSITION_FAILED,
                                sourceState,
                                null,
                                event,
                                String.format("状态机 [%s] 大批量流转触发 Fail-Fast 熔断中断 [entity=%s]: %s",
                                        machineId, entity, smEx.getMessage()),
                                smEx
                        );
                    }
                    failures.put(entity, smEx);
                }
            }
        }

        return BatchTransitionResult.of(entities.size(), successEntities, failures);
    }

    @Override
    public boolean canFire(S sourceState, E event, C context) {
        if (sourceState == null || event == null) {
            return false;
        }
        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;
        if (candidates == null || candidates.isEmpty()) {
            return isIdempotentHit(sourceState, event);
        }
        for (Transition<S, E, C> candidate : candidates) {
            try {
                if (candidate.isSatisfied(context)) {
                    return true;
                }
            } catch (Exception ex) {
                log.debug("状态机 [{}] 纯只读预检 canFire 卫语句求值异常 [from={}, event={}]: {}",
                        machineId, sourceState, event, ex.getMessage());
            }
        }
        return false;
    }

    @Override
    public TransitionResult<S> verifyTransition(S sourceState, E event, C context) {
        if (sourceState == null) {
            return TransitionResult.failure(null, event, "源状态不可为空");
        }
        if (event == null) {
            return TransitionResult.failure(sourceState, null, "触发事件不可为空");
        }
        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;
        if (candidates == null || candidates.isEmpty()) {
            if (isIdempotentHit(sourceState, event)) {
                return TransitionResult.idempotent(sourceState, event);
            }
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
                return TransitionResult.failure(
                        sourceState,
                        event,
                        String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句执行异常: %s",
                                machineId, sourceState, event, ex.getMessage()),
                        ex
                );
            }
            if (satisfied) {
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
        S targetState;
        try {
            targetState = matched.resolveTargetState(context);
        } catch (Exception ex) {
            return TransitionResult.failure(
                    sourceState,
                    event,
                    String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 确定目标状态时异常: %s",
                            machineId, sourceState, event, ex.getMessage()),
                    ex
            );
        }
        return TransitionResult.success(sourceState, targetState, event);
    }

    @Override
    public Set<E> getAvailableEvents(S sourceState, C context) {
        if (sourceState == null) {
            return Collections.emptySet();
        }
        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        Set<E> availableEvents = new LinkedHashSet<>();
        if (eventMap != null && !eventMap.isEmpty()) {
            for (Map.Entry<E, List<Transition<S, E, C>>> entry : eventMap.entrySet()) {
                E event = entry.getKey();
                List<Transition<S, E, C>> candidates = entry.getValue();
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                for (Transition<S, E, C> candidate : candidates) {
                    try {
                        if (candidate.isSatisfied(context)) {
                            availableEvents.add(event);
                            break;
                        }
                    } catch (Exception ex) {
                        log.debug("状态机 [{}] 计算可用事件卫语句求值异常 [from={}, event={}]: {}",
                                machineId, sourceState, event, ex.getMessage());
                    }
                }
            }
        }
        Set<E> idempEvents = idempotentTargets.get(sourceState);
        if (idempEvents != null) {
            availableEvents.addAll(idempEvents);
        }
        return Collections.unmodifiableSet(availableEvents);
    }

    @Override
    public void executeActions(S sourceState, S targetState, E event, C context) {
        if (sourceState == null || targetState == null || event == null) {
            return;
        }
        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        Transition<S, E, C> matched = null;
        for (Transition<S, E, C> candidate : candidates) {
            try {
                if (candidate.isSatisfied(context)) {
                    S resolved = candidate.resolveTargetState(context);
                    if (Objects.equals(resolved, targetState)) {
                        matched = candidate;
                        break;
                    }
                }
            } catch (Exception ignored) {
                // 忽略非目标分支求值异常
            }
        }
        if (matched == null) {
            for (Transition<S, E, C> candidate : candidates) {
                try {
                    if (candidate.isSatisfied(context)) {
                        matched = candidate;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (matched != null) {
            boolean isInternal = matched.getType() == TransitionType.INTERNAL
                    || (matched.getType() == TransitionType.CHOICE && Objects.equals(sourceState, targetState));

            if (!isInternal) {
                executeExitActions(sourceState, targetState, event, context);
            }

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

            if (!isInternal) {
                executeEntryActions(sourceState, targetState, event, context);
            }

            // 调度事务提交后动作
            schedulePostCommitActions(matched.getPostCommitActions(), sourceState, targetState, event, context);
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
            if (isIdempotentHit(sourceState, event)) {
                log.info("状态机 [{}] 幂等命中 [IDEMPOTENT_HIT]: 当前已处于目标状态 [{}], 重复事件 [{}] 放行，跳过动作执行",
                        machineId, sourceState, event);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
                notifyInterceptorsAfter(sourceState, sourceState, event, context, elapsed);
                return TransitionResult.idempotent(sourceState, event);
            }
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

        boolean isInternal = matched.getType() == TransitionType.INTERNAL
                || (matched.getType() == TransitionType.CHOICE && Objects.equals(sourceState, targetState));

        if (!isInternal) {
            try {
                executeExitActions(sourceState, targetState, event, context);
            } catch (StateMachineException smEx) {
                notifyInterceptorsException(sourceState, event, context, smEx);
                return TransitionResult.failure(sourceState, event, smEx.getMessage(), smEx.getCause());
            } catch (Exception ex) {
                StateMachineException smEx = new StateMachineException(
                        StateMachineResultCode.STATE_LIFECYCLE_FAILED,
                        sourceState,
                        targetState,
                        event,
                        String.format("状态机 [%s] 退出状态 [%s] 生命周期动作执行失败 [target=%s, event=%s]: %s",
                                machineId, sourceState, targetState, event, ex.getMessage()),
                        ex
                );
                notifyInterceptorsException(sourceState, event, context, smEx);
                return TransitionResult.failure(sourceState, event, smEx.getMessage(), ex);
            }
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

        if (!isInternal) {
            try {
                executeEntryActions(sourceState, targetState, event, context);
            } catch (StateMachineException smEx) {
                notifyInterceptorsException(sourceState, event, context, smEx);
                return TransitionResult.failure(sourceState, event, smEx.getMessage(), smEx.getCause());
            } catch (Exception ex) {
                StateMachineException smEx = new StateMachineException(
                        StateMachineResultCode.STATE_LIFECYCLE_FAILED,
                        sourceState,
                        targetState,
                        event,
                        String.format("状态机 [%s] 进入状态 [%s] 生命周期动作执行失败 [source=%s, event=%s]: %s",
                                machineId, targetState, sourceState, event, ex.getMessage()),
                        ex
                );
                notifyInterceptorsException(sourceState, event, context, smEx);
                return TransitionResult.failure(sourceState, event, smEx.getMessage(), ex);
            }
        }

        // 调度事务提交后动作
        schedulePostCommitActions(matched.getPostCommitActions(), sourceState, targetState, event, context);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
        notifyInterceptorsAfter(sourceState, targetState, event, context, elapsed);

        return TransitionResult.success(sourceState, targetState, event);
    }

    private boolean isIdempotentHit(S sourceState, E event) {
        if (sourceState == null || event == null) {
            return false;
        }
        Set<E> events = idempotentTargets.get(sourceState);
        return events != null && events.contains(event);
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

    private void schedulePostCommitActions(List<Action<S, E, C>> postCommitActions,
                                           S sourceState, S targetState, E event, C context) {
        if (postCommitActions == null || postCommitActions.isEmpty()) {
            return;
        }
        if (SPRING_TX_PRESENT && SpringTransactionSynchronizer.isSynchronizationActive()) {
            SpringTransactionSynchronizer.registerAfterCommit(() ->
                    executePostCommitActions(postCommitActions, sourceState, targetState, event, context)
            );
        } else {
            executePostCommitActions(postCommitActions, sourceState, targetState, event, context);
        }
    }

    private void executePostCommitActions(List<Action<S, E, C>> postCommitActions,
                                          S sourceState, S targetState, E event, C context) {
        if (postCommitActions == null || postCommitActions.isEmpty()) {
            return;
        }
        for (Action<S, E, C> action : postCommitActions) {
            try {
                action.execute(sourceState, targetState, event, context);
            } catch (Exception ex) {
                log.error("状态机 [{}] 事务提交后动作 (postCommitAction) 执行异常 [{} -> {} on {}]: {}",
                        machineId, sourceState, targetState, event, ex.getMessage(), ex);
                notifyInterceptorsException(sourceState, event, context, ex);
            }
        }
    }

    private void executeExitActions(S sourceState, S targetState, E event, C context) {
        StateLifecycle<S, E, C> lifecycle = stateLifecycleMap.get(sourceState);
        if (lifecycle == null || !lifecycle.hasExitActions()) {
            return;
        }
        for (Action<S, E, C> action : lifecycle.getExitActions()) {
            try {
                action.execute(sourceState, targetState, event, context);
            } catch (Exception ex) {
                throw new StateMachineException(
                        StateMachineResultCode.STATE_LIFECYCLE_FAILED,
                        sourceState,
                        targetState,
                        event,
                        String.format("状态机 [%s] 退出状态 [%s] 生命周期动作执行失败 [target=%s, event=%s]: %s",
                                machineId, sourceState, targetState, event, ex.getMessage()),
                        ex
                );
            }
        }
    }

    private void executeEntryActions(S sourceState, S targetState, E event, C context) {
        StateLifecycle<S, E, C> lifecycle = stateLifecycleMap.get(targetState);
        if (lifecycle == null || !lifecycle.hasEntryActions()) {
            return;
        }
        for (Action<S, E, C> action : lifecycle.getEntryActions()) {
            try {
                action.execute(sourceState, targetState, event, context);
            } catch (Exception ex) {
                throw new StateMachineException(
                        StateMachineResultCode.STATE_LIFECYCLE_FAILED,
                        sourceState,
                        targetState,
                        event,
                        String.format("状态机 [%s] 进入状态 [%s] 生命周期动作执行失败 [source=%s, event=%s]: %s",
                                machineId, targetState, sourceState, event, ex.getMessage()),
                        ex
                );
            }
        }
    }

    /**
     * Spring 事务同步基础设施提供者（静态内部类延迟加载，实现缺依赖或非活跃环境下的物理类隔离）
     */
    private static final class SpringTransactionSynchronizer {
        static boolean isSynchronizationActive() {
            return org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive();
        }

        static void registerAfterCommit(Runnable task) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    }
            );
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
