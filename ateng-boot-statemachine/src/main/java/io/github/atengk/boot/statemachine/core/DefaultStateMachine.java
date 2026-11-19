package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.BatchSliceUtil;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.cas.CasUpdater;
import io.github.atengk.boot.statemachine.model.BatchOptions;
import io.github.atengk.boot.statemachine.model.BatchTransitionResult;
import io.github.atengk.boot.statemachine.model.StateLifecycle;
import io.github.atengk.boot.statemachine.model.TransitionAuditEvent;
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
    private final Map<E, List<Transition<S, E, C>>> idempotentTransitionsByEvent;
    private final Map<S, StateLifecycle<S, E, C>> stateLifecycleMap;
    private final Set<S> allStates;
    private final Set<E> allEvents;
    private final List<StateTransitionAuditListener<? super S, ? super E, ? super C>> auditListeners;

    public DefaultStateMachine(String machineId,
                               Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap,
                               List<StateMachineInterceptor<? super S, ? super E, ? super C>> interceptors,
                               Map<S, StateLifecycle<S, E, C>> stateLifecycleMap,
                               List<StateTransitionAuditListener<? super S, ? super E, ? super C>> auditListeners) {
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
        List<StateMachineInterceptor<? super S, ? super E, ? super C>> supportedInterceptors = new ArrayList<>();
        if (interceptors != null) {
            for (StateMachineInterceptor<? super S, ? super E, ? super C> interceptor : interceptors) {
                if (interceptor != null && interceptor.supports(machineId) && !supportedInterceptors.contains(interceptor)) {
                    supportedInterceptors.add(interceptor);
                }
            }
        }
        this.interceptors = new CopyOnWriteArrayList<>(supportedInterceptors);

        Map<E, List<Transition<S, E, C>>> idempMap = new LinkedHashMap<>();
        Set<S> collectedStates = new LinkedHashSet<>();
        Set<E> collectedEvents = new LinkedHashSet<>();
        for (Map.Entry<S, Map<E, List<Transition<S, E, C>>>> sourceEntry : deepImmutableMap.entrySet()) {
            collectedStates.add(sourceEntry.getKey());
            for (Map.Entry<E, List<Transition<S, E, C>>> eventEntry : sourceEntry.getValue().entrySet()) {
                collectedEvents.add(eventEntry.getKey());
                for (Transition<S, E, C> transition : eventEntry.getValue()) {
                    collectedStates.addAll(transition.getPossibleTargetStates());
                    if (transition.isIdempotent()) {
                        idempMap.computeIfAbsent(transition.getEvent(), k -> new ArrayList<>()).add(transition);
                    }
                }
            }
        }
        Map<E, List<Transition<S, E, C>>> immutableIdempMap = new LinkedHashMap<>();
        for (Map.Entry<E, List<Transition<S, E, C>>> entry : idempMap.entrySet()) {
            immutableIdempMap.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        this.idempotentTransitionsByEvent = Collections.unmodifiableMap(immutableIdempMap);
        this.stateLifecycleMap = stateLifecycleMap != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(stateLifecycleMap))
                : Collections.emptyMap();

        collectedStates.addAll(this.stateLifecycleMap.keySet());
        this.allStates = Collections.unmodifiableSet(collectedStates);
        this.allEvents = Collections.unmodifiableSet(collectedEvents);

        this.auditListeners = new CopyOnWriteArrayList<>(auditListeners != null ? auditListeners : Collections.emptyList());
    }

    public DefaultStateMachine(String machineId,
                               Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap,
                               List<StateMachineInterceptor<? super S, ? super E, ? super C>> interceptors,
                               Map<S, StateLifecycle<S, E, C>> stateLifecycleMap) {
        this(machineId, transitionMap, interceptors, stateLifecycleMap, Collections.emptyList());
    }

    public DefaultStateMachine(String machineId,
                               Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap,
                               List<StateMachineInterceptor<? super S, ? super E, ? super C>> interceptors) {
        this(machineId, transitionMap, interceptors, Collections.emptyMap(), Collections.emptyList());
    }

    public DefaultStateMachine(String machineId, Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap) {
        this(machineId, transitionMap, Collections.emptyList(), Collections.emptyMap(), Collections.emptyList());
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
        if (interceptor != null && interceptor.supports(this.machineId) && !this.interceptors.contains(interceptor)) {
            this.interceptors.add(interceptor);
        }
    }

    public boolean removeInterceptor(StateMachineInterceptor<? super S, ? super E, ? super C> interceptor) {
        if (interceptor == null) {
            return false;
        }
        return this.interceptors.remove(interceptor);
    }

    public List<StateTransitionAuditListener<? super S, ? super E, ? super C>> getAuditListeners() {
        return Collections.unmodifiableList(auditListeners);
    }

    public void addAuditListener(StateTransitionAuditListener<? super S, ? super E, ? super C> listener) {
        if (listener != null && !this.auditListeners.contains(listener)) {
            this.auditListeners.add(listener);
        }
    }

    public boolean removeAuditListener(StateTransitionAuditListener<? super S, ? super E, ? super C> listener) {
        if (listener == null) {
            return false;
        }
        return this.auditListeners.remove(listener);
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

        S targetState = null;
        try {
            Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
            List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;

            if (candidates == null || candidates.isEmpty()) {
                if (isIdempotentHit(sourceState, event, context)) {
                    recordIdempotentHit(sourceState, event, context, startTime, false);
                    return sourceState;
                }
                throw new StateMachineException(
                        StateMachineResultCode.TRANSITION_NOT_FOUND,
                        sourceState,
                        event,
                        String.format("状态机 [%s] 未找到从状态 [%s] 响应事件 [%s] 的合法跃迁路由", machineId, sourceState, event)
                );
            }

            Transition<S, E, C> matched = resolveMatchedTransition(sourceState, event, context, candidates);
            targetState = resolveTargetState(sourceState, event, context, matched);

            executeTransitionPipeline(matched, sourceState, targetState, event, context);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
            notifyInterceptorsAfter(sourceState, targetState, event, context, elapsed);
            notifyAuditListeners(sourceState, targetState, event, context, elapsed, true, null);

            return targetState;
        } catch (Exception ex) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
            S auditToState = targetState;
            if (auditToState == null && ex instanceof StateMachineException smEx && smEx.getTargetState() != null) {
                try {
                    @SuppressWarnings("unchecked")
                    S smTargetState = (S) smEx.getTargetState();
                    auditToState = smTargetState;
                } catch (Exception ignored) {
                    // 类型不兼容时降级为 null
                }
            }
            notifyInterceptorsException(sourceState, event, context, ex);
            notifyAuditListeners(sourceState, auditToState, event, context, elapsed, false, ex);
            throw ex;
        }
    }

    @Override
    public boolean canFire(S sourceState, E event, C context) {
        if (sourceState == null || event == null) {
            return false;
        }
        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;
        if (candidates == null || candidates.isEmpty()) {
            return isIdempotentHit(sourceState, event, context);
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
            return TransitionResult.failure(StateMachineResultCode.TRANSITION_NOT_FOUND, null, event, "源状态不可为空");
        }
        if (event == null) {
            return TransitionResult.failure(StateMachineResultCode.TRANSITION_NOT_FOUND, sourceState, null, "触发事件不可为空");
        }
        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;
        if (candidates == null || candidates.isEmpty()) {
            if (isIdempotentHit(sourceState, event, context)) {
                return TransitionResult.idempotent(sourceState, event);
            }
            return TransitionResult.failure(
                    StateMachineResultCode.TRANSITION_NOT_FOUND,
                    sourceState,
                    event,
                    String.format("状态机 [%s] 未找到从状态 [%s] 响应事件 [%s] 的合法跃迁路由", machineId, sourceState, event)
            );
        }
        Transition<S, E, C> matched = null;
        Throwable lastGuardException = null;
        for (Transition<S, E, C> candidate : candidates) {
            boolean satisfied = false;
            try {
                satisfied = candidate.isSatisfied(context);
            } catch (Exception ex) {
                lastGuardException = ex;
                log.debug("状态机 [{}] 纯只读预检 verifyTransition 卫语句求值异常 [from={}, event={}]: {}",
                        machineId, sourceState, event, ex.getMessage());
            }
            if (satisfied) {
                matched = candidate;
                break;
            }
        }
        if (matched == null) {
            if (lastGuardException != null) {
                return TransitionResult.failure(
                        StateMachineResultCode.CONDITION_FAILED,
                        sourceState,
                        event,
                        String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句执行异常: %s",
                                machineId, sourceState, event, lastGuardException.getMessage()),
                        lastGuardException
                );
            }
            return TransitionResult.failure(
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
            return TransitionResult.failure(
                    StateMachineResultCode.CONDITION_FAILED,
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
        for (E event : idempotentTransitionsByEvent.keySet()) {
            if (!availableEvents.contains(event) && isIdempotentHit(sourceState, event, context)) {
                availableEvents.add(event);
            }
        }
        return Collections.unmodifiableSet(availableEvents);
    }

    private void executeTransitionPipeline(Transition<S, E, C> matched, S sourceState, S targetState, E event, C context) {
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

    @Override
    public <T> S fireEventWithCas(
            T entity,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            C context,
            CasUpdater<T, S> casUpdater
    ) {
        return doFireEventWithCas(entity, getter, setter, event, context, casUpdater).targetState();
    }

    private <T> CasExecutionResult<S> doFireEventWithCas(
            T entity,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            C context,
            CasUpdater<T, S> casUpdater
    ) {
        if (entity == null) {
            throw new IllegalArgumentException("Target entity must not be null");
        }
        if (getter == null || setter == null) {
            throw new IllegalArgumentException("Getter and setter must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("Event must not be null");
        }
        if (casUpdater == null) {
            throw new IllegalArgumentException("CasUpdater must not be null");
        }

        S sourceState = getter.apply(entity);
        long startTime = System.nanoTime();
        S targetState = null;

        try {
            notifyInterceptorsBefore(sourceState, event, context);

            Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
            List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;
            if (candidates == null || candidates.isEmpty()) {
                if (isIdempotentHit(sourceState, event, context)) {
                    recordIdempotentHit(sourceState, event, context, startTime, true);
                    return new CasExecutionResult<>(sourceState, true);
                }
                throw new StateMachineException(
                        StateMachineResultCode.TRANSITION_NOT_FOUND,
                        sourceState,
                        event,
                        String.format("状态机 [%s] 未找到从状态 [%s] 响应事件 [%s] 的合法跃迁路由", machineId, sourceState, event)
                );
            }

            Transition<S, E, C> matched = resolveMatchedTransition(sourceState, event, context, candidates);
            targetState = resolveTargetState(sourceState, event, context, matched);

            // 执行数据库 CAS 乐观锁条件更新
            int rowsAffected = casUpdater.updateWithCas(entity, sourceState, targetState);
            if (rowsAffected <= 0) {
                throw new StateMachineException(
                        StateMachineResultCode.CONCURRENT_CAS_CONFLICT,
                        sourceState,
                        targetState,
                        event,
                        String.format("状态机 [%s] 并发 CAS 乐观锁更新冲突 [实体状态由 %s 跃迁至 %s 失败，受影响行数为 0]",
                                machineId, sourceState, targetState)
                );
            }

            // CAS 成功后回写实体内存状态
            setter.accept(entity, targetState);

            // 有序执行生命周期动作链
            executeTransitionPipeline(matched, sourceState, targetState, event, context);

            Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
            notifyInterceptorsAfter(sourceState, targetState, event, context, elapsed);
            notifyAuditListeners(sourceState, targetState, event, context, elapsed, true, null);

            return new CasExecutionResult<>(targetState, false);
        } catch (Exception ex) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
            notifyInterceptorsException(sourceState, event, context, ex);
            notifyAuditListeners(sourceState, targetState, event, context, elapsed, false, ex);
            throw ex;
        }
    }

    @Override
    public <T> TransitionResult<S> fireEventWithCasQuietly(
            T entity,
            Function<T, S> getter,
            BiConsumer<T, S> setter,
            E event,
            C context,
            CasUpdater<T, S> casUpdater
    ) {
        if (entity == null) {
            return TransitionResult.failure(StateMachineResultCode.CONDITION_FAILED, null, event, "Target entity must not be null");
        }
        if (getter == null || setter == null) {
            throw new IllegalArgumentException("Getter and setter must not be null");
        }
        if (casUpdater == null) {
            throw new IllegalArgumentException("CasUpdater must not be null");
        }
        S sourceState = getter.apply(entity);
        try {
            CasExecutionResult<S> outcome = doFireEventWithCas(entity, getter, setter, event, context, casUpdater);
            if (outcome.idempotent()) {
                return TransitionResult.idempotent(sourceState, event);
            }
            return TransitionResult.success(sourceState, outcome.targetState(), event);
        } catch (StateMachineException smEx) {
            return TransitionResult.failure(
                    resolveResultCode(smEx),
                    sourceState,
                    event,
                    smEx.getMessage(),
                    smEx.getCause() != null ? smEx.getCause() : smEx
            );
        } catch (Exception ex) {
            return TransitionResult.failure(
                    StateMachineResultCode.ACTION_EXECUTION_FAILED,
                    sourceState,
                    event,
                    String.format("状态机 [%s] 执行 CAS 更新时发生异常: %s", machineId, ex.getMessage()),
                    ex
            );
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
            if (isIdempotentHit(sourceState, event, context)) {
                recordIdempotentHit(sourceState, event, context, startTime, false);
                return TransitionResult.idempotent(sourceState, event);
            }
            StateMachineException ex = new StateMachineException(
                    StateMachineResultCode.TRANSITION_NOT_FOUND,
                    sourceState,
                    event,
                    String.format("状态机 [%s] 未找到从状态 [%s] 响应事件 [%s] 的合法跃迁路由", machineId, sourceState, event)
            );
            return handleQuietFailure(sourceState, event, context, startTime, ex);
        }

        Transition<S, E, C> matched;
        S targetState = null;
        try {
            matched = resolveMatchedTransition(sourceState, event, context, candidates);
            targetState = resolveTargetState(sourceState, event, context, matched);
            executeTransitionPipeline(matched, sourceState, targetState, event, context);
        } catch (StateMachineException smEx) {
            return handleQuietFailure(sourceState, event, context, startTime, smEx);
        } catch (Exception ex) {
            StateMachineException smEx = new StateMachineException(
                    StateMachineResultCode.ACTION_EXECUTION_FAILED,
                    sourceState,
                    targetState,
                    event,
                    String.format("状态机 [%s] 跃迁动作执行失败 [%s -> %s on %s]: %s", machineId, sourceState, targetState, event, ex.getMessage()),
                    ex
            );
            return handleQuietFailure(sourceState, event, context, startTime, smEx);
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
        notifyInterceptorsAfter(sourceState, targetState, event, context, elapsed);
        notifyAuditListeners(sourceState, targetState, event, context, elapsed, true, null);

        return TransitionResult.success(sourceState, targetState, event);
    }

    @SuppressWarnings("unchecked")
    private TransitionResult<S> handleQuietFailure(S sourceState, E event, C context, long startTime, StateMachineException ex) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
        notifyInterceptorsException(sourceState, event, context, ex);
        S targetState = ex.getTargetState() != null ? (S) ex.getTargetState() : null;
        notifyAuditListeners(sourceState, targetState, event, context, elapsed, false, ex);
        return TransitionResult.failure(resolveResultCode(ex), sourceState, event, ex.getMessage(), ex.getCause());
    }

    private Transition<S, E, C> resolveMatchedTransition(S sourceState, E event, C context, List<Transition<S, E, C>> candidates) {
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
                return candidate;
            }
        }
        throw new StateMachineException(
                StateMachineResultCode.CONDITION_FAILED,
                sourceState,
                event,
                String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 的前置条件卫语句校验不通过", machineId, sourceState, event)
        );
    }

    private S resolveTargetState(S sourceState, E event, C context, Transition<S, E, C> matched) {
        try {
            return matched.resolveTargetState(context);
        } catch (Exception ex) {
            throw new StateMachineException(
                    StateMachineResultCode.CONDITION_FAILED,
                    sourceState,
                    event,
                    String.format("状态机 [%s] 从状态 [%s] 响应事件 [%s] 确定目标状态时异常: %s", machineId, sourceState, event, ex.getMessage()),
                    ex
            );
        }
    }

    private StateMachineResultCode resolveResultCode(StateMachineException ex) {
        if (ex.getResultCode() instanceof StateMachineResultCode resultCode) {
            return resultCode;
        }
        if (ex.getCode() != null) {
            for (StateMachineResultCode value : StateMachineResultCode.values()) {
                if (Objects.equals(value.getCode(), ex.getCode())) {
                    return value;
                }
            }
        }
        return StateMachineResultCode.CONDITION_FAILED;
    }

    private boolean isIdempotentHit(S currentState, E event, C context) {
        if (currentState == null || event == null) {
            return false;
        }
        List<Transition<S, E, C>> candidates = idempotentTransitionsByEvent.get(event);
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (Transition<S, E, C> transition : candidates) {
            if (transition instanceof ChoiceTransition) {
                if (context != null) {
                    try {
                        S resolved = transition.resolveTargetState(context);
                        if (Objects.equals(resolved, currentState)) {
                            return true;
                        }
                    } catch (Exception ex) {
                        log.debug("状态机 [{}] Choice 幂等目标态求值异常 [state={}, event={}]: {}",
                                machineId, currentState, event, ex.getMessage());
                    }
                } else if (transition.getPossibleTargetStates().contains(currentState)) {
                    return true;
                }
            } else if (Objects.equals(transition.resolveTargetState(context), currentState)) {
                if (context == null) {
                    return true;
                }
                try {
                    if (transition.isSatisfied(context)) {
                        return true;
                    }
                } catch (Exception ex) {
                    log.debug("状态机 [{}] 幂等卫语句求值异常 [state={}, event={}]: {}",
                            machineId, currentState, event, ex.getMessage());
                }
            }
        }
        return false;
    }

    private void recordIdempotentHit(S state, E event, C context, long startTime, boolean casMode) {
        if (casMode) {
            log.info("状态机 [{}] CAS 幂等命中 [IDEMPOTENT_HIT]: 当前已处于目标状态 [{}], 重复事件 [{}] 放行，跳过 CAS 与动作执行",
                    machineId, state, event);
        } else {
            log.info("状态机 [{}] 幂等命中 [IDEMPOTENT_HIT]: 当前已处于目标状态 [{}], 重复事件 [{}] 放行，跳过动作执行",
                    machineId, state, event);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startTime);
        notifyInterceptorsAfter(state, state, event, context, elapsed);
        notifyAuditListeners(state, state, event, context, elapsed, true, true, null);
    }

    private record CasExecutionResult<S>(S targetState, boolean idempotent) {
    }

    private void notifyInterceptorsBefore(S sourceState, E event, C context) {
        for (StateMachineInterceptor<? super S, ? super E, ? super C> interceptor : interceptors) {
            try {
                interceptor.beforeTransition(machineId, sourceState, event, context);
            } catch (Exception ex) {
                log.warn("状态机 [{}] 拦截器 [{}] 前置通知执行异常: {}", machineId, interceptor.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        }
    }

    private void notifyInterceptorsAfter(S sourceState, S targetState, E event, C context, Duration elapsed) {
        for (StateMachineInterceptor<? super S, ? super E, ? super C> interceptor : interceptors) {
            try {
                interceptor.afterTransition(machineId, sourceState, targetState, event, context, elapsed);
            } catch (Exception ex) {
                log.warn("状态机 [{}] 拦截器 [{}] 后置通知执行异常: {}", machineId, interceptor.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        }
    }

    private void notifyInterceptorsException(S sourceState, E event, C context, Exception ex) {
        for (StateMachineInterceptor<? super S, ? super E, ? super C> interceptor : interceptors) {
            try {
                interceptor.onException(machineId, sourceState, event, context, ex);
            } catch (Exception interceptorEx) {
                log.warn("状态机 [{}] 拦截器 [{}] 异常回调处理失败: {}", machineId, interceptor.getClass().getSimpleName(), interceptorEx.getMessage(), interceptorEx);
            }
        }
    }

    private void notifyAuditListeners(S fromState, S toState, E event, C context, Duration elapsed, boolean success, Throwable cause) {
        notifyAuditListeners(fromState, toState, event, context, elapsed, success, false, cause);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void notifyAuditListeners(S fromState, S toState, E event, C context, Duration elapsed, boolean success, boolean idempotent, Throwable cause) {
        if (auditListeners.isEmpty()) {
            return;
        }
        String traceId = org.slf4j.MDC.get("traceId");
        if (traceId == null) {
            traceId = org.slf4j.MDC.get("trace_id");
        }
        String operator = UserContextHolder.get();
        String tenantId = TenantContextHolder.getTenantId();
        TransitionAuditEvent<S, E, C> auditEvent = TransitionAuditEvent.<S, E, C>builder()
                .machineId(machineId)
                .fromState(fromState)
                .toState(toState)
                .event(event)
                .context(context)
                .operator(operator)
                .tenantId(tenantId)
                .traceId(traceId)
                .elapsed(elapsed)
                .success(success)
                .idempotent(idempotent)
                .cause(cause)
                .build();

        for (StateTransitionAuditListener listener : auditListeners) {
            try {
                listener.onTransitionCompleted(auditEvent);
            } catch (Exception ex) {
                log.warn("状态机 [{}] 审计监听器 [{}] 处理异常: {}",
                        machineId, listener.getClass().getSimpleName(), ex.getMessage(), ex);
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
    public Set<S> getAllStates() {
        return allStates;
    }

    @Override
    public Set<E> getAllEvents() {
        return allEvents;
    }

    @Override
    public Set<S> getPossibleTargetStates(S sourceState, E event) {
        if (sourceState == null || event == null) {
            return Collections.emptySet();
        }
        Map<E, List<Transition<S, E, C>>> eventMap = transitionMap.get(sourceState);
        List<Transition<S, E, C>> candidates = eventMap != null ? eventMap.get(event) : null;
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptySet();
        }
        Set<S> targets = new LinkedHashSet<>();
        for (Transition<S, E, C> candidate : candidates) {
            targets.addAll(candidate.getPossibleTargetStates());
        }
        return Collections.unmodifiableSet(targets);
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
