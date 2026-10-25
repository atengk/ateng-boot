package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外部状态跃迁单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class ExternalTransitionTest {

    enum TestState {
        WAIT_PAY, PAID, SHIPPED
    }

    enum TestEvent {
        PAY_SUCCESS, SHIP
    }

    static class OrderContext {
        private int amount;
        private final List<String> logs = new ArrayList<>();

        public OrderContext(int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }

        public void setAmount(int amount) {
            this.amount = amount;
        }

        public List<String> getLogs() {
            return logs;
        }
    }

    @Test
    @DisplayName("测试标准外部跃迁成功流转与动作执行")
    void testExternalTransitionSuccess() {
        StateMachineBuilder<TestState, TestEvent, OrderContext> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(TestState.WAIT_PAY)
                .to(TestState.PAID)
                .on(TestEvent.PAY_SUCCESS)
                .when(ctx -> ctx.getAmount() > 0)
                .perform((from, to, event, ctx) -> ctx.getLogs().add("Action 1: from " + from + " to " + to))
                .perform(ctx -> ctx.getLogs().add("Action 2: amount " + ctx.getAmount()));

        StateMachine<TestState, TestEvent, OrderContext> machine = builder.build("ORDER_FLOW");

        OrderContext ctx = new OrderContext(100);
        TestState target = machine.fireEvent(TestState.WAIT_PAY, TestEvent.PAY_SUCCESS, ctx);

        assertThat(target).isEqualTo(TestState.PAID);
        assertThat(ctx.getLogs()).containsExactly(
                "Action 1: from WAIT_PAY to PAID",
                "Action 2: amount 100"
        );
    }

    @Test
    @DisplayName("测试前置卫语句条件不满足时拦截")
    void testConditionFailed() {
        StateMachineBuilder<TestState, TestEvent, OrderContext> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(TestState.WAIT_PAY)
                .to(TestState.PAID)
                .on(TestEvent.PAY_SUCCESS)
                .when(ctx -> ctx.getAmount() > 0);

        StateMachine<TestState, TestEvent, OrderContext> machine = builder.build("ORDER_FLOW");

        OrderContext ctx = new OrderContext(0);

        // Fail-Fast 模式
        assertThatThrownBy(() -> machine.fireEvent(TestState.WAIT_PAY, TestEvent.PAY_SUCCESS, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.CONDITION_FAILED.getCode());
                    assertThat(smEx.getSourceState()).isEqualTo(TestState.WAIT_PAY);
                    assertThat(smEx.getEvent()).isEqualTo(TestEvent.PAY_SUCCESS);
                });

        // 静默探测模式
        TransitionResult<TestState> result = machine.fireEventQuietly(TestState.WAIT_PAY, TestEvent.PAY_SUCCESS, ctx);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getFailureReason()).contains("前置条件卫语句校验不通过");
    }

    @Test
    @DisplayName("测试未注册的跃迁路由触发失败")
    void testTransitionNotFound() {
        StateMachineBuilder<TestState, TestEvent, OrderContext> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(TestState.WAIT_PAY)
                .to(TestState.PAID)
                .on(TestEvent.PAY_SUCCESS);

        StateMachine<TestState, TestEvent, OrderContext> machine = builder.build("ORDER_FLOW");

        OrderContext ctx = new OrderContext(100);

        // 尝试从 PAID 状态触发 PAY_SUCCESS
        assertThatThrownBy(() -> machine.fireEvent(TestState.PAID, TestEvent.PAY_SUCCESS, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.TRANSITION_NOT_FOUND.getCode());
                });

        TransitionResult<TestState> result = machine.fireEventQuietly(TestState.PAID, TestEvent.PAY_SUCCESS, ctx);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getFailureReason()).contains("未找到从状态 [PAID] 响应事件 [PAY_SUCCESS] 的合法跃迁路由");
    }

    @Test
    @DisplayName("测试 Action 抛出异常时阻断流转并保持因果链")
    void testActionExceptionAbort() {
        StateMachineBuilder<TestState, TestEvent, OrderContext> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(TestState.WAIT_PAY)
                .to(TestState.PAID)
                .on(TestEvent.PAY_SUCCESS)
                .perform((from, to, event, ctx) -> {
                    throw new IllegalStateException("扣减库存失败");
                });

        StateMachine<TestState, TestEvent, OrderContext> machine = builder.build("ORDER_FLOW");

        OrderContext ctx = new OrderContext(100);

        assertThatThrownBy(() -> machine.fireEvent(TestState.WAIT_PAY, TestEvent.PAY_SUCCESS, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.ACTION_EXECUTION_FAILED.getCode());
                    assertThat(smEx.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(smEx.getCause()).hasMessage("扣减库存失败");
                });

        TransitionResult<TestState> result = machine.fireEventQuietly(TestState.WAIT_PAY, TestEvent.PAY_SUCCESS, ctx);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("测试 Condition 卫语句抛出异常时转译为 StateMachineException 并保持因果链")
    void testConditionExceptionPreservesCause() {
        StateMachineBuilder<TestState, TestEvent, OrderContext> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(TestState.WAIT_PAY)
                .to(TestState.PAID)
                .on(TestEvent.PAY_SUCCESS)
                .when(ctx -> {
                    throw new IllegalStateException("风控前置校验异常");
                });

        StateMachine<TestState, TestEvent, OrderContext> machine = builder.build("ORDER_FLOW");
        OrderContext ctx = new OrderContext(100);

        // 1. Fail-Fast 模式
        assertThatThrownBy(() -> machine.fireEvent(TestState.WAIT_PAY, TestEvent.PAY_SUCCESS, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.CONDITION_FAILED.getCode());
                    assertThat(smEx.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(smEx.getCause()).hasMessage("风控前置校验异常");
                });

        // 2. 静默探测模式
        TransitionResult<TestState> result = machine.fireEventQuietly(TestState.WAIT_PAY, TestEvent.PAY_SUCCESS, ctx);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getFailureReason()).contains("前置条件卫语句执行异常");
        assertThat(result.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(result.getCause()).hasMessage("风控前置校验异常");
    }

    @Test
    @DisplayName("测试 Transition 构造方法对源状态和事件的防御性空值校验")
    void testTransitionConstructorNullDefense() {
        assertThatThrownBy(() -> new Transition<>(null, TestState.PAID, TestEvent.PAY_SUCCESS, TransitionType.EXTERNAL, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceState must not be null");

        assertThatThrownBy(() -> new Transition<>(TestState.WAIT_PAY, TestState.PAID, null, TransitionType.EXTERNAL, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event must not be null");
    }
}
