package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多源汇聚状态跃迁单元测试 ([S1, S2, ...] -> S3)
 *
 * @author Ateng
 * @since 2026-10-25
 */
class MultiFromTransitionTest {

    enum OrderState {
        WAIT_PAY, PAID, WAIT_DELIVER, DELIVERED, CLOSED
    }

    enum OrderEvent {
        CANCEL, SHIP
    }

    static class CancelContext {
        private final boolean allowCancel;
        private final String reason;
        private final List<String> logs = new ArrayList<>();

        public CancelContext(boolean allowCancel, String reason) {
            this.allowCancel = allowCancel;
            this.reason = reason;
        }

        public boolean isAllowCancel() {
            return allowCancel;
        }

        public String getReason() {
            return reason;
        }

        public List<String> getLogs() {
            return logs;
        }
    }

    @Test
    @DisplayName("测试变长参数多源状态统一收敛至 CLOSED 状态")
    void testMultiFromConvergenceWithVarargs() {
        StateMachineBuilder<OrderState, OrderEvent, CancelContext> builder = StateMachineBuilderFactory.create();
        builder.multiFromTransition()
                .from(OrderState.WAIT_PAY, OrderState.PAID, OrderState.WAIT_DELIVER)
                .to(OrderState.CLOSED)
                .on(OrderEvent.CANCEL)
                .when(CancelContext::isAllowCancel)
                .perform((from, to, event, ctx) -> ctx.getLogs().add("Canceled from " + from + ", reason: " + ctx.getReason()))
                .perform(ctx -> ctx.getLogs().add("Cancel audit logged"));

        StateMachine<OrderState, OrderEvent, CancelContext> machine = builder.build("CANCEL_MACHINE");

        // 1. 从 WAIT_PAY 取消
        CancelContext ctx1 = new CancelContext(true, "用户主动放弃支付");
        OrderState target1 = machine.fireEvent(OrderState.WAIT_PAY, OrderEvent.CANCEL, ctx1);
        assertThat(target1).isEqualTo(OrderState.CLOSED);
        assertThat(ctx1.getLogs()).containsExactly(
                "Canceled from WAIT_PAY, reason: 用户主动放弃支付",
                "Cancel audit logged"
        );

        // 2. 从 PAID 取消
        CancelContext ctx2 = new CancelContext(true, "用户拍错重新下单");
        OrderState target2 = machine.fireEvent(OrderState.PAID, OrderEvent.CANCEL, ctx2);
        assertThat(target2).isEqualTo(OrderState.CLOSED);
        assertThat(ctx2.getLogs()).containsExactly(
                "Canceled from PAID, reason: 用户拍错重新下单",
                "Cancel audit logged"
        );

        // 3. 从 WAIT_DELIVER 取消
        CancelContext ctx3 = new CancelContext(true, "商家缺货缺件");
        OrderState target3 = machine.fireEvent(OrderState.WAIT_DELIVER, OrderEvent.CANCEL, ctx3);
        assertThat(target3).isEqualTo(OrderState.CLOSED);
        assertThat(ctx3.getLogs()).containsExactly(
                "Canceled from WAIT_DELIVER, reason: 商家缺货缺件",
                "Cancel audit logged"
        );
    }

    @Test
    @DisplayName("测试集合入参多源状态统一收敛")
    void testMultiFromConvergenceWithCollection() {
        StateMachineBuilder<OrderState, OrderEvent, CancelContext> builder = StateMachineBuilderFactory.create();
        List<OrderState> cancellableStates = Arrays.asList(OrderState.WAIT_PAY, OrderState.PAID);

        builder.multiFromTransition()
                .from(cancellableStates)
                .to(OrderState.CLOSED)
                .on(OrderEvent.CANCEL);

        StateMachine<OrderState, OrderEvent, CancelContext> machine = builder.build("CANCEL_MACHINE_COLL");

        CancelContext ctx = new CancelContext(true, "取消测试");
        assertThat(machine.fireEvent(OrderState.WAIT_PAY, OrderEvent.CANCEL, ctx)).isEqualTo(OrderState.CLOSED);
        assertThat(machine.fireEvent(OrderState.PAID, OrderEvent.CANCEL, ctx)).isEqualTo(OrderState.CLOSED);

        // 不在集合内的状态触发取消失败
        assertThatThrownBy(() -> machine.fireEvent(OrderState.DELIVERED, OrderEvent.CANCEL, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.TRANSITION_NOT_FOUND.getCode());
                });
    }

    @Test
    @DisplayName("测试多源跃迁前置条件不满足时阻断")
    void testMultiFromConditionFailed() {
        StateMachineBuilder<OrderState, OrderEvent, CancelContext> builder = StateMachineBuilderFactory.create();
        builder.multiFromTransition()
                .from(OrderState.WAIT_PAY, OrderState.PAID)
                .to(OrderState.CLOSED)
                .on(OrderEvent.CANCEL)
                .when(CancelContext::isAllowCancel);

        StateMachine<OrderState, OrderEvent, CancelContext> machine = builder.build("CANCEL_GUARD_MACHINE");

        CancelContext ctx = new CancelContext(false, "不允许取消");

        // Fail-Fast 模式
        assertThatThrownBy(() -> machine.fireEvent(OrderState.WAIT_PAY, OrderEvent.CANCEL, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.CONDITION_FAILED.getCode());
                    assertThat(smEx.getSourceState()).isEqualTo(OrderState.WAIT_PAY);
                    assertThat(smEx.getEvent()).isEqualTo(OrderEvent.CANCEL);
                });

        // 静默探测模式
        TransitionResult<OrderState> result = machine.fireEventQuietly(OrderState.PAID, OrderEvent.CANCEL, ctx);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getFailureReason()).contains("前置条件卫语句校验不通过");
    }

    @Test
    @DisplayName("测试多源跃迁 Action 异常阻断并保留因果链")
    void testMultiFromActionException() {
        StateMachineBuilder<OrderState, OrderEvent, CancelContext> builder = StateMachineBuilderFactory.create();
        builder.multiFromTransition()
                .from(OrderState.WAIT_PAY, OrderState.PAID)
                .to(OrderState.CLOSED)
                .on(OrderEvent.CANCEL)
                .perform((from, to, event, ctx) -> {
                    throw new RuntimeException("退款账户解冻失败");
                });

        StateMachine<OrderState, OrderEvent, CancelContext> machine = builder.build("ACTION_ERR_MACHINE");

        CancelContext ctx = new CancelContext(true, "测试异常");

        assertThatThrownBy(() -> machine.fireEvent(OrderState.WAIT_PAY, OrderEvent.CANCEL, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.ACTION_EXECUTION_FAILED.getCode());
                    assertThat(smEx.getCause()).isInstanceOf(RuntimeException.class);
                    assertThat(smEx.getCause()).hasMessage("退款账户解冻失败");
                });

        TransitionResult<OrderState> result = machine.fireEventQuietly(OrderState.PAID, OrderEvent.CANCEL, ctx);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getCause()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("测试边界异常防卫: 空入参与不完整配置")
    void testEdgeCasesAndValidation() {
        StateMachineBuilder<OrderState, OrderEvent, CancelContext> builder = StateMachineBuilderFactory.create();

        // 1. null 或空变长入参
        OrderState[] nullArray = null;
        assertThatThrownBy(() -> builder.multiFromTransition().from(nullArray))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceStates must not be empty");

        assertThatThrownBy(() -> builder.multiFromTransition().from(new OrderState[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceStates must not be empty");

        // 2. 包含 null 元素
        assertThatThrownBy(() -> builder.multiFromTransition().from(OrderState.WAIT_PAY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceState in sourceStates must not be null");

        // 3. 集合入参 null 或空
        assertThatThrownBy(() -> builder.multiFromTransition().from((List<OrderState>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceStates must not be empty");

        assertThatThrownBy(() -> builder.multiFromTransition().from(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceStates must not be empty");

        // 4. to 入参 null
        assertThatThrownBy(() -> builder.multiFromTransition().from(OrderState.WAIT_PAY).to(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetState must not be null");

        // 5. on 入参 null
        assertThatThrownBy(() -> builder.multiFromTransition().from(OrderState.WAIT_PAY).to(OrderState.CLOSED).on(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event must not be null");

        // 6. perform consumer null
        assertThatThrownBy(() -> builder.multiFromTransition()
                .from(OrderState.WAIT_PAY).to(OrderState.CLOSED).on(OrderEvent.CANCEL)
                .perform((java.util.function.Consumer<CancelContext>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Action consumer must not be null");

        // 7. 不完整配置在 build 阶段拦截
        StateMachineBuilder<OrderState, OrderEvent, CancelContext> incompleteBuilder = StateMachineBuilderFactory.create();
        incompleteBuilder.multiFromTransition().from(OrderState.WAIT_PAY).to(OrderState.CLOSED); // 漏掉 on

        assertThatThrownBy(() -> incompleteBuilder.build("INCOMPLETE_MACHINE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incomplete multi-from transition configuration");
    }
}
