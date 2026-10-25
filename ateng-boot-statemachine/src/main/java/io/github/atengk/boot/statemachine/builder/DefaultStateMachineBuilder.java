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
    public MultiFromTransitionBuilder.From<S, E, C> multiFromTransition() {
        MultiFromTransitionHolder<S, E, C> holder = new MultiFromTransitionHolder<>();
        transitionHolders.add(holder);
        return holder;
    }

    @Override
    public ChoiceTransitionBuilder.From<S, E, C> choiceTransition() {
        ChoiceTransitionHolder<S, E, C> holder = new ChoiceTransitionHolder<>();
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
            List<Transition<S, E, C>> transitions = holder.buildTransitions();
            if (transitions == null || transitions.isEmpty()) {
                continue;
            }

            for (Transition<S, E, C> transition : transitions) {
                S source = transition.getSourceState();
                E event = transition.getEvent();

                transitionMap.computeIfAbsent(source, k -> new LinkedHashMap<>())
                        .computeIfAbsent(event, k -> new ArrayList<>())
                        .add(transition);
            }
        }

        return new DefaultStateMachine<>(machineId, transitionMap);
    }

    private interface TransitionHolder<S, E, C> {
        List<Transition<S, E, C>> buildTransitions();
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
        public List<Transition<S, E, C>> buildTransitions() {
            if (sourceState == null || targetState == null || event == null) {
                throw new IllegalStateException("Incomplete external transition configuration: sourceState, targetState, and event must all be set");
            }
            return Collections.singletonList(new Transition<>(sourceState, targetState, event, TransitionType.EXTERNAL, conditions, actions));
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
        public List<Transition<S, E, C>> buildTransitions() {
            if (sourceState == null || event == null) {
                throw new IllegalStateException("Incomplete internal transition configuration: sourceState and event must be set");
            }
            return Collections.singletonList(new Transition<>(sourceState, sourceState, event, TransitionType.INTERNAL, conditions, actions));
        }
    }

    private static class MultiFromTransitionHolder<S, E, C> implements
            TransitionHolder<S, E, C>,
            MultiFromTransitionBuilder.From<S, E, C>,
            MultiFromTransitionBuilder.To<S, E, C>,
            MultiFromTransitionBuilder.On<S, E, C>,
            MultiFromTransitionBuilder.WhenOrPerform<S, E, C> {

        private final Set<S> sourceStates = new LinkedHashSet<>();
        private S targetState;
        private E event;
        private final List<Condition<C>> conditions = new ArrayList<>();
        private final List<Action<S, E, C>> actions = new ArrayList<>();

        @SafeVarargs
        @Override
        public final MultiFromTransitionBuilder.To<S, E, C> from(S... sourceStates) {
            if (sourceStates == null || sourceStates.length == 0) {
                throw new IllegalArgumentException("sourceStates must not be empty");
            }
            for (S state : sourceStates) {
                if (state == null) {
                    throw new IllegalArgumentException("sourceState in sourceStates must not be null");
                }
                this.sourceStates.add(state);
            }
            return this;
        }

        @Override
        public MultiFromTransitionBuilder.To<S, E, C> from(Collection<S> sourceStates) {
            if (sourceStates == null || sourceStates.isEmpty()) {
                throw new IllegalArgumentException("sourceStates must not be empty");
            }
            for (S state : sourceStates) {
                if (state == null) {
                    throw new IllegalArgumentException("sourceState in sourceStates must not be null");
                }
                this.sourceStates.add(state);
            }
            return this;
        }

        @Override
        public MultiFromTransitionBuilder.On<S, E, C> to(S targetState) {
            if (targetState == null) {
                throw new IllegalArgumentException("targetState must not be null");
            }
            this.targetState = targetState;
            return this;
        }

        @Override
        public MultiFromTransitionBuilder.WhenOrPerform<S, E, C> on(E event) {
            if (event == null) {
                throw new IllegalArgumentException("event must not be null");
            }
            this.event = event;
            return this;
        }

        @Override
        public MultiFromTransitionBuilder.WhenOrPerform<S, E, C> when(Condition<C> condition) {
            if (condition != null) {
                this.conditions.add(condition);
            }
            return this;
        }

        @Override
        public MultiFromTransitionBuilder.WhenOrPerform<S, E, C> perform(Action<S, E, C> action) {
            if (action != null) {
                this.actions.add(action);
            }
            return this;
        }

        @Override
        public List<Transition<S, E, C>> buildTransitions() {
            if (sourceStates.isEmpty() || targetState == null || event == null) {
                throw new IllegalStateException("Incomplete multi-from transition configuration: sourceStates, targetState, and event must all be set");
            }
            List<Transition<S, E, C>> transitions = new ArrayList<>();
            for (S source : sourceStates) {
                transitions.add(new Transition<>(source, targetState, event, TransitionType.EXTERNAL, conditions, actions));
            }
            return transitions;
        }
    }

    private static class ChoiceTransitionHolder<S, E, C> implements
            TransitionHolder<S, E, C>,
            ChoiceTransitionBuilder.From<S, E, C>,
            ChoiceTransitionBuilder.On<S, E, C>,
            ChoiceTransitionBuilder.BranchWhen<S, E, C>,
            ChoiceTransitionBuilder.BranchTo<S, E, C>,
            ChoiceTransitionBuilder.BranchChoice<S, E, C>,
            ChoiceTransitionBuilder.PerformOrEnd<S, E, C> {

        private S sourceState;
        private E event;
        private final List<ChoiceBranch<S, C>> branches = new ArrayList<>();
        private S defaultTargetState;
        private final List<Action<S, E, C>> actions = new ArrayList<>();

        private List<Condition<C>> currentBranchConditions = new ArrayList<>();

        @Override
        public ChoiceTransitionBuilder.On<S, E, C> from(S sourceState) {
            if (sourceState == null) {
                throw new IllegalArgumentException("sourceState must not be null");
            }
            this.sourceState = sourceState;
            return this;
        }

        @Override
        public ChoiceTransitionBuilder.BranchWhen<S, E, C> on(E event) {
            if (event == null) {
                throw new IllegalArgumentException("event must not be null");
            }
            this.event = event;
            return this;
        }

        @Override
        public ChoiceTransitionBuilder.BranchTo<S, E, C> when(Condition<C> condition) {
            if (condition == null) {
                throw new IllegalArgumentException("condition must not be null");
            }
            this.currentBranchConditions.add(condition);
            return this;
        }

        @Override
        public ChoiceTransitionBuilder.BranchChoice<S, E, C> to(S targetState) {
            if (targetState == null) {
                throw new IllegalArgumentException("targetState must not be null");
            }
            if (this.currentBranchConditions.isEmpty()) {
                throw new IllegalStateException("Branch must have at least one condition before specifying targetState");
            }
            this.branches.add(new ChoiceBranch<>(this.currentBranchConditions, targetState));
            this.currentBranchConditions = new ArrayList<>();
            return this;
        }

        @Override
        public ChoiceTransitionBuilder.PerformOrEnd<S, E, C> defaultTo(S defaultTargetState) {
            if (defaultTargetState == null) {
                throw new IllegalArgumentException("defaultTargetState must not be null");
            }
            this.defaultTargetState = defaultTargetState;
            return this;
        }

        @Override
        public ChoiceTransitionBuilder.PerformOrEnd<S, E, C> perform(Action<S, E, C> action) {
            if (action != null) {
                this.actions.add(action);
            }
            return this;
        }

        @Override
        public List<Transition<S, E, C>> buildTransitions() {
            if (sourceState == null || event == null || branches.isEmpty() || defaultTargetState == null) {
                throw new IllegalStateException("Incomplete choice transition configuration: sourceState, event, at least one branch, and defaultTargetState must all be set");
            }
            return Collections.singletonList(new ChoiceTransition<>(sourceState, event, branches, defaultTargetState, actions));
        }
    }
}
