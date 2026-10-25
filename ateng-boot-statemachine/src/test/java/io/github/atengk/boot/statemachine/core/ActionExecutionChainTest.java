package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动作执行链时序与中断机制单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class ActionExecutionChainTest {

    enum State {
        S1, S2
    }

    enum Event {
        E1
    }

    static class ChainContext {
        final List<Integer> stepLogs = new ArrayList<>();
    }

    @Test
    @DisplayName("测试多个 Action 严格按声明顺序同步执行")
    void testSequentialActionChain() {
        StateMachineBuilder<State, Event, ChainContext> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(State.S1)
                .to(State.S2)
                .on(Event.E1)
                .perform((from, to, event, ctx) -> ctx.stepLogs.add(1))
                .perform((from, to, event, ctx) -> ctx.stepLogs.add(2))
                .perform((from, to, event, ctx) -> ctx.stepLogs.add(3));

        StateMachine<State, Event, ChainContext> machine = builder.build("CHAIN_MACHINE");

        ChainContext ctx = new ChainContext();
        State target = machine.fireEvent(State.S1, Event.E1, ctx);

        assertThat(target).isEqualTo(State.S2);
        assertThat(ctx.stepLogs).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("测试中间 Action 发生异常时后续 Action 绝对被中断不执行")
    void testActionFailureAbortsRemainingActions() {
        StateMachineBuilder<State, Event, ChainContext> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(State.S1)
                .to(State.S2)
                .on(Event.E1)
                .perform((from, to, event, ctx) -> ctx.stepLogs.add(1))
                .perform((from, to, event, ctx) -> {
                    ctx.stepLogs.add(2);
                    throw new RuntimeException("Action 2 失败");
                })
                .perform((from, to, event, ctx) -> ctx.stepLogs.add(3));

        StateMachine<State, Event, ChainContext> machine = builder.build("CHAIN_MACHINE");

        ChainContext ctx = new ChainContext();

        assertThatThrownBy(() -> machine.fireEvent(State.S1, Event.E1, ctx))
                .isInstanceOf(StateMachineException.class)
                .hasCauseInstanceOf(RuntimeException.class);

        // Action 1 与 Action 2 已执行，Action 3 坚决不执行
        assertThat(ctx.stepLogs).containsExactly(1, 2);
    }
}
