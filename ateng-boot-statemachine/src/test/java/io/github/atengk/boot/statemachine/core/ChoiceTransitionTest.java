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
 * Choice 动态条件分支路由跃迁单元测试 (S1 -> S2 if C1 else S3)
 *
 * @author Ateng
 * @since 2026-10-25
 */
class ChoiceTransitionTest {

    enum OrderState {
        PAID, WAIT_DELIVER, REFUNDING, WAIT_AUDIT
    }

    enum OrderEvent {
        PAY_SUCCESS
    }

    static class RiskContext {
        private final int riskScore;
        private final boolean blacklisted;
        private final boolean vip;
        private final List<String> logs = new ArrayList<>();

        public RiskContext(int riskScore, boolean blacklisted, boolean vip) {
            this.riskScore = riskScore;
            this.blacklisted = blacklisted;
            this.vip = vip;
        }

        public int getRiskScore() {
            return riskScore;
        }

        public boolean isBlacklisted() {
            return blacklisted;
        }

        public boolean isVip() {
            return vip;
        }

        public List<String> getLogs() {
            return logs;
        }
    }

    @Test
    @DisplayName("测试 Choice 分支路由：风控通过进入 WAIT_DELIVER")
    void testChoiceBranch1Matched() {
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> builder = StateMachineBuilderFactory.create();
        builder.choiceTransition()
                .from(OrderState.PAID)
                .on(OrderEvent.PAY_SUCCESS)
                .when(ctx -> ctx.getRiskScore() >= 80).to(OrderState.WAIT_DELIVER)
                .when(ctx -> ctx.getRiskScore() < 50 || ctx.isBlacklisted()).to(OrderState.REFUNDING)
                .defaultTo(OrderState.WAIT_AUDIT)
                .perform((from, to, event, ctx) -> ctx.getLogs().add(String.format("Routed from %s to %s on %s", from, to, event)))
                .perform(ctx -> ctx.getLogs().add("Risk inspection finished"));

        StateMachine<OrderState, OrderEvent, RiskContext> machine = builder.build("RISK_CHOICE_MACHINE");

        RiskContext ctx = new RiskContext(90, false, false);
        OrderState target = machine.fireEvent(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx);

        assertThat(target).isEqualTo(OrderState.WAIT_DELIVER);
        assertThat(ctx.getLogs()).containsExactly(
                "Routed from PAID to WAIT_DELIVER on PAY_SUCCESS",
                "Risk inspection finished"
        );
    }

    @Test
    @DisplayName("测试 Choice 分支路由：高风险被拦截进入 REFUNDING")
    void testChoiceBranch2Matched() {
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> builder = StateMachineBuilderFactory.create();
        builder.choiceTransition()
                .from(OrderState.PAID)
                .on(OrderEvent.PAY_SUCCESS)
                .when(ctx -> ctx.getRiskScore() >= 80).to(OrderState.WAIT_DELIVER)
                .when(ctx -> ctx.getRiskScore() < 50 || ctx.isBlacklisted()).to(OrderState.REFUNDING)
                .defaultTo(OrderState.WAIT_AUDIT)
                .perform((from, to, event, ctx) -> ctx.getLogs().add("Target: " + to));

        StateMachine<OrderState, OrderEvent, RiskContext> machine = builder.build("RISK_CHOICE_MACHINE");

        // 低分黑名单用户
        RiskContext ctx = new RiskContext(40, true, false);
        OrderState target = machine.fireEvent(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx);

        assertThat(target).isEqualTo(OrderState.REFUNDING);
        assertThat(ctx.getLogs()).containsExactly("Target: REFUNDING");
    }

    @Test
    @DisplayName("测试 Choice 分支路由：无匹配条件时降级至 defaultTo 兜底目标状态")
    void testChoiceFallbackToDefault() {
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> builder = StateMachineBuilderFactory.create();
        builder.choiceTransition()
                .from(OrderState.PAID)
                .on(OrderEvent.PAY_SUCCESS)
                .when(ctx -> ctx.getRiskScore() >= 80).to(OrderState.WAIT_DELIVER)
                .when(ctx -> ctx.getRiskScore() < 50 || ctx.isBlacklisted()).to(OrderState.REFUNDING)
                .defaultTo(OrderState.WAIT_AUDIT)
                .perform((from, to, event, ctx) -> ctx.getLogs().add("Fallback Target: " + to));

        StateMachine<OrderState, OrderEvent, RiskContext> machine = builder.build("RISK_CHOICE_MACHINE");

        // 分数 65 (介于 50 与 80 之间，非黑名单)
        RiskContext ctx = new RiskContext(65, false, false);
        OrderState target = machine.fireEvent(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx);

        assertThat(target).isEqualTo(OrderState.WAIT_AUDIT);
        assertThat(ctx.getLogs()).containsExactly("Fallback Target: WAIT_AUDIT");
    }

    @Test
    @DisplayName("测试 Choice 分支按声明顺序依次求值，命中首个即刻短路路由")
    void testChoiceSequentialEvaluationShortCircuit() {
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> builder = StateMachineBuilderFactory.create();
        builder.choiceTransition()
                .from(OrderState.PAID)
                .on(OrderEvent.PAY_SUCCESS)
                // 分支 1: VIP 直接通过
                .when(RiskContext::isVip).to(OrderState.WAIT_DELIVER)
                // 分支 2: 黑名单退款（即使是 VIP 且黑名单，由于分支 1 在前，命中分支 1）
                .when(RiskContext::isBlacklisted).to(OrderState.REFUNDING)
                .defaultTo(OrderState.WAIT_AUDIT);

        StateMachine<OrderState, OrderEvent, RiskContext> machine = builder.build("ORDER_SHORT_CIRCUIT");

        RiskContext bothMatches = new RiskContext(30, true, true);
        OrderState target = machine.fireEvent(OrderState.PAID, OrderEvent.PAY_SUCCESS, bothMatches);

        assertThat(target).isEqualTo(OrderState.WAIT_DELIVER);
    }

    @Test
    @DisplayName("测试单分支复合条件 (AND 语义)")
    void testChoiceMultipleConditionsPerBranch() {
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> builder = StateMachineBuilderFactory.create();
        builder.choiceTransition()
                .from(OrderState.PAID)
                .on(OrderEvent.PAY_SUCCESS)
                // 分支同时要求 score >= 80 且是 VIP
                .when(ctx -> ctx.getRiskScore() >= 80)
                .when(RiskContext::isVip)
                .to(OrderState.WAIT_DELIVER)
                .defaultTo(OrderState.WAIT_AUDIT);

        StateMachine<OrderState, OrderEvent, RiskContext> machine = builder.build("AND_CONDITION_MACHINE");

        // 满足 score >= 80 但不是 VIP -> 走 defaultTo
        RiskContext ctx1 = new RiskContext(90, false, false);
        assertThat(machine.fireEvent(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx1)).isEqualTo(OrderState.WAIT_AUDIT);

        // 两者都满足 -> 走 WAIT_DELIVER
        RiskContext ctx2 = new RiskContext(90, false, true);
        assertThat(machine.fireEvent(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx2)).isEqualTo(OrderState.WAIT_DELIVER);
    }

    @Test
    @DisplayName("测试 Choice 静默探测模式")
    void testChoiceQuietProbe() {
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> builder = StateMachineBuilderFactory.create();
        builder.choiceTransition()
                .from(OrderState.PAID)
                .on(OrderEvent.PAY_SUCCESS)
                .when(ctx -> ctx.getRiskScore() >= 80).to(OrderState.WAIT_DELIVER)
                .defaultTo(OrderState.WAIT_AUDIT);

        StateMachine<OrderState, OrderEvent, RiskContext> machine = builder.build("QUIET_CHOICE_MACHINE");

        RiskContext ctx = new RiskContext(95, false, false);
        TransitionResult<OrderState> result = machine.fireEventQuietly(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx);

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.getSourceState()).isEqualTo(OrderState.PAID);
        assertThat(result.getTargetState()).isEqualTo(OrderState.WAIT_DELIVER);
        assertThat(result.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("测试 Choice Action 执行异常阻断与因果链保留")
    void testChoiceActionException() {
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> builder = StateMachineBuilderFactory.create();
        builder.choiceTransition()
                .from(OrderState.PAID)
                .on(OrderEvent.PAY_SUCCESS)
                .when(ctx -> ctx.getRiskScore() >= 80).to(OrderState.WAIT_DELIVER)
                .defaultTo(OrderState.WAIT_AUDIT)
                .perform((from, to, event, ctx) -> {
                    throw new IllegalStateException("通知仓储履约系统超时");
                });

        StateMachine<OrderState, OrderEvent, RiskContext> machine = builder.build("ACTION_EXCEPTION_MACHINE");

        RiskContext ctx = new RiskContext(90, false, false);

        assertThatThrownBy(() -> machine.fireEvent(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.ACTION_EXECUTION_FAILED.getCode());
                    assertThat(smEx.getSourceState()).isEqualTo(OrderState.PAID);
                    assertThat(smEx.getTargetState()).isEqualTo(OrderState.WAIT_DELIVER);
                    assertThat(smEx.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(smEx.getCause()).hasMessage("通知仓储履约系统超时");
                });

        TransitionResult<OrderState> result = machine.fireEventQuietly(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("测试 Choice 边界异常防卫与不完整配置校验")
    void testEdgeCasesAndValidation() {
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> builder = StateMachineBuilderFactory.create();

        // 1. from null
        assertThatThrownBy(() -> builder.choiceTransition().from(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceState must not be null");

        // 2. on null
        assertThatThrownBy(() -> builder.choiceTransition().from(OrderState.PAID).on(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event must not be null");

        // 3. when null
        assertThatThrownBy(() -> builder.choiceTransition().from(OrderState.PAID).on(OrderEvent.PAY_SUCCESS).when(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("condition must not be null");

        // 4. to null
        assertThatThrownBy(() -> builder.choiceTransition().from(OrderState.PAID).on(OrderEvent.PAY_SUCCESS).when(ctx -> true).to(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetState must not be null");

        // 5. defaultTo null
        assertThatThrownBy(() -> builder.choiceTransition()
                .from(OrderState.PAID).on(OrderEvent.PAY_SUCCESS)
                .when(ctx -> true).to(OrderState.WAIT_DELIVER)
                .defaultTo(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTargetState must not be null");

        // 6. perform consumer null
        assertThatThrownBy(() -> builder.choiceTransition()
                .from(OrderState.PAID).on(OrderEvent.PAY_SUCCESS)
                .when(ctx -> true).to(OrderState.WAIT_DELIVER)
                .defaultTo(OrderState.WAIT_AUDIT)
                .perform((java.util.function.Consumer<RiskContext>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Action consumer must not be null");

        // 7. 漏掉 defaultTo 时构建被拦截
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> incompleteBuilder = StateMachineBuilderFactory.create();
        incompleteBuilder.choiceTransition()
                .from(OrderState.PAID).on(OrderEvent.PAY_SUCCESS)
                .when(ctx -> true).to(OrderState.WAIT_DELIVER); // 漏掉 defaultTo

        assertThatThrownBy(() -> incompleteBuilder.build("INCOMPLETE_CHOICE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incomplete choice transition configuration");
    }

    @Test
    @DisplayName("测试 Choice 分支条件执行抛出异常时的因果链转译与静默承载")
    void testChoiceConditionExceptionHandling() {
        StateMachineBuilder<OrderState, OrderEvent, RiskContext> builder = StateMachineBuilderFactory.create();
        builder.choiceTransition()
                .from(OrderState.PAID)
                .on(OrderEvent.PAY_SUCCESS)
                .when(ctx -> {
                    throw new RuntimeException("风控系统超时");
                })
                .to(OrderState.WAIT_AUDIT)
                .defaultTo(OrderState.WAIT_DELIVER);

        StateMachine<OrderState, OrderEvent, RiskContext> machine = builder.build("RISK_CHOICE_EX");
        RiskContext ctx = new RiskContext(100, true, false);

        // 1. Fail-Fast 模式
        assertThatThrownBy(() -> machine.fireEvent(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.CONDITION_FAILED.getCode());
                    assertThat(smEx.getCause()).isInstanceOf(RuntimeException.class);
                    assertThat(smEx.getCause()).hasMessage("风控系统超时");
                });

        // 2. 静默探测模式
        TransitionResult<OrderState> result = machine.fireEventQuietly(OrderState.PAID, OrderEvent.PAY_SUCCESS, ctx);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getFailureReason()).contains("确定目标状态时异常");
        assertThat(result.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(result.getCause()).hasMessage("风控系统超时");
    }
}
