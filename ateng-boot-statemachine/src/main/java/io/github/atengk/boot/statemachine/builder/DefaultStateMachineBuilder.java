package io.github.atengk.boot.statemachine.builder;

import io.github.atengk.boot.statemachine.core.*;

import java.util.*;

/**
 * 强类型状态机 Fluent DSL 构建器默认实现
 *
 * @param <S> 业务状态类型
 * @param <E> 触发事件类型
 * @param <C> 业务上下文类型
 * @author Ateng
 * @since 2026-10-25
 */
public class DefaultStateMachineBuilder<S, E, C> implements StateMachineBuilder<S, E, C> {

    private final List<TransitionHolder<S, E, C>> transitionHolders = new ArrayList<>();

    @Override
    public ExternalTransitionBuilder.From<S, E, C> externalTransition() {
        ExternalTransitionHolder<S, E, C> holder = new ExternalTransitionHolder<>();
        transitionHolders.add(holder);
        return holder;
    }

    @Override
    public InternalTransitionBuilder.From<S, E, C> internalTransition() {
        InternalTransitionHolder<S, E, C> holder = new InternalTransitionHolder<>();
        transitionHolders.add(holder);
        return holder;
    }

    @Override
    public StateMachine<S, E, C> build(String machineId) {
        if (machineId == null || machineId.trim().isEmpty()) {
            throw new IllegalArgumentException("machineId must not be blank");
        }

        Map<S, Map<E, List<Transition<S, E, C>>>> transitionMap = new LinkedHashMap<>();

        for (TransitionHolder<S, E, C> holder : transitionHolders) {
            Transition<S, E, C> transition = holder.buildTransition();
            if (transition == null) {
                continue;
            }

            S source = transition.getSourceState();
            E event = transition.getEvent();

            transitionMap.computeIfAbsent(source, k -> new LinkedHashMap<>())
                    .computeIfAbsent(event, k -> new ArrayList<>())
                    .add(transition);
        }

        return new DefaultStateMachine<>(machineId, transitionMap);
    }

    private interface TransitionHolder<S, E, C> {
        Transition<S, E, C> buildTransition();
    }

    private static class ExternalTransitionHolder<S, E, C> implements
            TransitionHolder<S, E, C>,
            ExternalTransitionBuilder.From<S, E, C>,
            ExternalTransitionBuilder.To<S, E, C>,
            ExternalTransitionBuilder.On<S, E, C>,
            ExternalTransitionBuilder.WhenOrPerform<S, E, C> {

        private S sourceState;
        private S targetState;
        private E event;
        private final List<Condition<C>> conditions = new ArrayList<>();
        private final List<Action<S, E, C>> actions = new ArrayList<>();

        @Override
        public ExternalTransitionBuilder.To<S, E, C> from(S sourceState) {
            if (sourceState == null) {
                throw new IllegalArgumentException("sourceState must not be null");
            }
            this.sourceState = sourceState;
            return this;
        }

        @Override
        public ExternalTransitionBuilder.On<S, E, C> to(S targetState) {
            if (targetState == null) {
                throw new IllegalArgumentException("targetState must not be null");
            }
            this.targetState = targetState;
            return this;
        }

        @Override
        public ExternalTransitionBuilder.WhenOrPerform<S, E, C> on(E event) {
            if (event == null) {
                throw new IllegalArgumentException("event must not be null");
            }
            this.event = event;
            return this;
        }

        @Override
        public ExternalTransitionBuilder.WhenOrPerform<S, E, C> when(Condition<C> condition) {
            if (condition != null) {
                this.conditions.add(condition);
            }
            return this;
        }

        @Override
        public ExternalTransitionBuilder.WhenOrPerform<S, E, C> perform(Action<S, E, C> action) {
            if (action != null) {
                this.actions.add(action);
            }
            return this;
        }

        @Override
        public Transition<S, E, C> buildTransition() {
            if (sourceState == null || targetState == null || event == null) {
                throw new IllegalStateException("Incomplete external transition configuration: sourceState, targetState, and event must all be set");
            }
            return new Transition<>(sourceState, targetState, event, TransitionType.EXTERNAL, conditions, actions);
        }
    }

    private static class InternalTransitionHolder<S, E, C> implements
            TransitionHolder<S, E, C>,
            InternalTransitionBuilder.From<S, E, C>,
            InternalTransitionBuilder.On<S, E, C>,
            InternalTransitionBuilder.WhenOrPerform<S, E, C> {

        private S sourceState;
        private E event;
        private final List<Condition<C>> conditions = new ArrayList<>();
        private final List<Action<S, E, C>> actions = new ArrayList<>();

        @Override
        public InternalTransitionBuilder.On<S, E, C> from(S sourceState) {
            if (sourceState == null) {
                throw new IllegalArgumentException("sourceState must not be null");
            }
            this.sourceState = sourceState;
            return this;
        }

        @Override
        public InternalTransitionBuilder.WhenOrPerform<S, E, C> on(E event) {
            if (event == null) {
                throw new IllegalArgumentException("event must not be null");
            }
            this.event = event;
            return this;
        }

        @Override
        public InternalTransitionBuilder.WhenOrPerform<S, E, C> when(Condition<C> condition) {
            if (condition != null) {
                this.conditions.add(condition);
            }
            return this;
        }

        @Override
        public InternalTransitionBuilder.WhenOrPerform<S, E, C> perform(Action<S, E, C> action) {
            if (action != null) {
                this.actions.add(action);
            }
            return this;
        }

        @Override
        public Transition<S, E, C> buildTransition() {
            if (sourceState == null || event == null) {
                throw new IllegalStateException("Incomplete internal transition configuration: sourceState and event must be set");
            }
            return new Transition<>(sourceState, sourceState, event, TransitionType.INTERNAL, conditions, actions);
        }
    }
}
