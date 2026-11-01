package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯只读预检探测与可用事件计算单元测试（零副作用守护）
 *
 * @author Ateng
 * @since 2026-11-01
 */
class ReadOnlyProbeTest {

    enum State {
        WAIT_PAY, PAID, DELIVERING, CLOSED, REFUNDED
    }

    enum Event {
        PAY, SHIP, UPDATE_TRACKING, CANCEL, RISK_REJECT
    }

    static class OrderEntity {
        private State state;
        private final String orderSn;

        public OrderEntity(State state, String orderSn) {
            this.state = state;
            this.orderSn = orderSn;
        }

        public State getState() {
            return state;
        }

        public void setState(State state) {
            this.state = state;
        }

        public String getOrderSn() {
            return orderSn;
        }
    }

    static class OrderContext {
        private final int amount;
        private final boolean riskFlag;
        private final boolean throwInGuard;

        public OrderContext(int amount, boolean riskFlag, boolean throwInGuard) {
            this.amount = amount;
            this.riskFlag = riskFlag;
            this.throwInGuard = throwInGuard;
        }

        public int getAmount() {
            return amount;
        }

        public boolean isRiskFlag() {
            return riskFlag;
        }

        public boolean isThrowInGuard() {
            return throwInGuard;
        }
    }

    @Test
    @DisplayName("测试 canFire 与 verifyTransition 纯只读预检（强断言 0 次 Action 执行）")
    void testReadOnlyProbeZeroSideEffects() {
        AtomicInteger actionCounter = new AtomicInteger(0);

        StateMachineBuilder<State, Event, OrderContext> builder = StateMachineBuilderFactory.create();

        // 1. 外部主干跃迁：WAIT_PAY -> PAID (PAY)
        builder.externalTransition()
                .from(State.WAIT_PAY)
                .to(State.PAID)
                .on(Event.PAY)
                .when(ctx -> ctx != null && ctx.getAmount() > 0)
                .perform((from, to, event, ctx) -> actionCounter.incrementAndGet());

        // 2. Choice 条件分支跃迁：PAID -> (风控判断) -> REFUNDED(高危) / DELIVERING(正常)
        builder.choiceTransition()
                .from(State.PAID)
                .on(Event.SHIP)
                .when(ctx -> ctx != null && ctx.isRiskFlag())
                .to(State.REFUNDED)
                .defaultTo(State.DELIVERING)
                .perform((from, to, event, ctx) -> actionCounter.incrementAndGet());

        // 3. 内部自跃迁：DELIVERING -> DELIVERING (UPDATE_TRACKING)
        builder.internalTransition()
                .from(State.DELIVERING)
                .on(Event.UPDATE_TRACKING)
                .perform((from, to, event, ctx) -> actionCounter.incrementAndGet());

        // 4. 多源汇聚跃迁：[WAIT_PAY, PAID] -> CLOSED (CANCEL)
        builder.multiFromTransition()
                .from(State.WAIT_PAY, State.PAID)
                .to(State.CLOSED)
                .on(Event.CANCEL)
                .perform((from, to, event, ctx) -> actionCounter.incrementAndGet());

        StateMachine<State, Event, OrderContext> machine = builder.build("PROBE_TEST_MACHINE");

        // --- 验证 canFire ---
        // 合法外部流转，条件满足
        assertThat(machine.canFire(State.WAIT_PAY, Event.PAY, new OrderContext(100, false, false))).isTrue();
        // 条件不满足
        assertThat(machine.canFire(State.WAIT_PAY, Event.PAY, new OrderContext(0, false, false))).isFalse();
        // 未匹配路由
        assertThat(machine.canFire(State.WAIT_PAY, Event.SHIP, new OrderContext(100, false, false))).isFalse();
        // 空值安全
        assertThat(machine.canFire(null, Event.PAY, new OrderContext(100, false, false))).isFalse();
        assertThat(machine.canFire(State.WAIT_PAY, null, new OrderContext(100, false, false))).isFalse();

        // --- 验证 verifyTransition ---
        // 成功外部流转
        TransitionResult<State> r1 = machine.verifyTransition(State.WAIT_PAY, Event.PAY, new OrderContext(50, false, false));
        assertThat(r1.isAccepted()).isTrue();
        assertThat(r1.getSourceState()).isEqualTo(State.WAIT_PAY);
        assertThat(r1.getTargetState()).isEqualTo(State.PAID);
        assertThat(r1.getFailureReason()).isNull();

        // 卫语句校验失败
        TransitionResult<State> r2 = machine.verifyTransition(State.WAIT_PAY, Event.PAY, new OrderContext(-1, false, false));
        assertThat(r2.isAccepted()).isFalse();
        assertThat(r2.getTargetState()).isEqualTo(State.WAIT_PAY);
        assertThat(r2.getFailureReason()).contains("前置条件卫语句校验不通过");

        // 未找到路由
        TransitionResult<State> r3 = machine.verifyTransition(State.CLOSED, Event.PAY, new OrderContext(100, false, false));
        assertThat(r3.isAccepted()).isFalse();
        assertThat(r3.getFailureReason()).contains("未找到从状态 [CLOSED] 响应事件 [PAY] 的合法跃迁路由");

        // --- 验证 Choice 分支动态试算 ---
        // 高危风控分支
        TransitionResult<State> choiceHighRisk = machine.verifyTransition(State.PAID, Event.SHIP, new OrderContext(100, true, false));
        assertThat(choiceHighRisk.isAccepted()).isTrue();
        assertThat(choiceHighRisk.getTargetState()).isEqualTo(State.REFUNDED);

        // 默认放行分支
        TransitionResult<State> choiceNormal = machine.verifyTransition(State.PAID, Event.SHIP, new OrderContext(100, false, false));
        assertThat(choiceNormal.isAccepted()).isTrue();
        assertThat(choiceNormal.getTargetState()).isEqualTo(State.DELIVERING);

        // --- 验证内部自跃迁试算 ---
        TransitionResult<State> internalResult = machine.verifyTransition(State.DELIVERING, Event.UPDATE_TRACKING, null);
        assertThat(internalResult.isAccepted()).isTrue();
        assertThat(internalResult.getTargetState()).isEqualTo(State.DELIVERING);

        // --- 验证多源汇聚跃迁 ---
        assertThat(machine.canFire(State.WAIT_PAY, Event.CANCEL, null)).isTrue();
        assertThat(machine.canFire(State.PAID, Event.CANCEL, null)).isTrue();
        assertThat(machine.verifyTransition(State.PAID, Event.CANCEL, null).getTargetState()).isEqualTo(State.CLOSED);

        // 核心断言：无论多少次预检，动作执行次数必须且只能为 0
        assertThat(actionCounter.get()).isZero();
    }

    @Test
    @DisplayName("测试 Guard 卫语句抛出异常时的容错与安全捕获")
    void testGuardConditionExceptionResilience() {
        StateMachineBuilder<State, Event, OrderContext> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(State.WAIT_PAY)
                .to(State.PAID)
                .on(Event.PAY)
                .when(ctx -> {
                    if (ctx != null && ctx.isThrowInGuard()) {
                        throw new IllegalStateException("Simulated NPE in guard assertion");
                    }
                    return true;
                });

        StateMachine<State, Event, OrderContext> machine = builder.build("GUARD_EXCEPTION_TEST");

        OrderContext errorContext = new OrderContext(100, false, true);

        // canFire 遇异常安全返回 false，绝不抛出异常阻断调用方
        boolean canFireResult = machine.canFire(State.WAIT_PAY, Event.PAY, errorContext);
        assertThat(canFireResult).isFalse();

        // verifyTransition 包装异常因果链
        TransitionResult<State> result = machine.verifyTransition(State.WAIT_PAY, Event.PAY, errorContext);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getFailureReason()).contains("前置条件卫语句执行异常");
        assertThat(result.getCause()).isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated NPE in guard assertion");
    }

    @Test
    @DisplayName("测试 getAvailableEvents 计算可用事件列表")
    void testGetAvailableEvents() {
        StateMachineBuilder<State, Event, OrderContext> builder = StateMachineBuilderFactory.create();

        // 从 WAIT_PAY 可以 PAY（金额>0）或 CANCEL（无条件）
        builder.externalTransition()
                .from(State.WAIT_PAY)
                .to(State.PAID)
                .on(Event.PAY)
                .when(ctx -> ctx != null && ctx.getAmount() > 0);

        builder.externalTransition()
                .from(State.WAIT_PAY)
                .to(State.CLOSED)
                .on(Event.CANCEL);

        // 从 PAID 可以 SHIP
        builder.externalTransition()
                .from(State.PAID)
                .to(State.DELIVERING)
                .on(Event.SHIP);

        StateMachine<State, Event, OrderContext> machine = builder.build("AVAILABLE_EVENTS_TEST");

        // 当金额 > 0 时，WAIT_PAY 包含 PAY 与 CANCEL
        Set<Event> eventsWithAmount = machine.getAvailableEvents(State.WAIT_PAY, new OrderContext(100, false, false));
        assertThat(eventsWithAmount).containsExactlyInAnyOrder(Event.PAY, Event.CANCEL);

        // 当金额 <= 0 时，PAY 的卫语句不通过，仅包含 CANCEL
        Set<Event> eventsZeroAmount = machine.getAvailableEvents(State.WAIT_PAY, new OrderContext(0, false, false));
        assertThat(eventsZeroAmount).containsExactly(Event.CANCEL);

        // 无上下文重载，PAY 卫语句不通过，仅包含 CANCEL
        Set<Event> defaultEvents = machine.getAvailableEvents(State.WAIT_PAY);
        assertThat(defaultEvents).containsExactly(Event.CANCEL);

        // PAID 状态仅包含 SHIP
        assertThat(machine.getAvailableEvents(State.PAID)).containsExactly(Event.SHIP);

        // 无任何跃迁的 CLOSED 状态返回空集合（严禁返回 null）
        Set<Event> closedEvents = machine.getAvailableEvents(State.CLOSED);
        assertThat(closedEvents).isNotNull().isEmpty();

        // null 源状态保底返回空集合
        assertThat(machine.getAvailableEvents(null)).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("测试实体 Lambda 访问门面的只读预检")
    void testEntityLambdaAccessorProbe() {
        StateMachineBuilder<State, Event, OrderContext> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(State.WAIT_PAY)
                .to(State.PAID)
                .on(Event.PAY)
                .when(ctx -> ctx != null && ctx.getAmount() > 0);

        StateMachine<State, Event, OrderContext> machine = builder.build("ENTITY_PROBE_TEST");

        OrderEntity order = new OrderEntity(State.WAIT_PAY, "SN-1001");
        OrderContext validContext = new OrderContext(100, false, false);

        // 实体 canFire
        assertThat(machine.canFire(order, OrderEntity::getState, Event.PAY, validContext)).isTrue();
        assertThat(machine.canFire(order, OrderEntity::getState, Event.SHIP)).isFalse();

        // 实体 verifyTransition
        TransitionResult<State> result = machine.verifyTransition(order, OrderEntity::getState, Event.PAY, validContext);
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.getTargetState()).isEqualTo(State.PAID);
        // 实体本身状态保持未变（纯只读）
        assertThat(order.getState()).isEqualTo(State.WAIT_PAY);

        // 实体 getAvailableEvents
        Set<Event> availableEvents = machine.getAvailableEvents(order, OrderEntity::getState, validContext);
        assertThat(availableEvents).containsExactly(Event.PAY);

        // 空防御
        assertThat(machine.canFire(null, OrderEntity::getState, Event.PAY)).isFalse();
        assertThat(machine.canFire(order, null, Event.PAY)).isFalse();
        assertThat(machine.verifyTransition(null, OrderEntity::getState, Event.PAY).isAccepted()).isFalse();
        assertThatThrownBy(() -> machine.verifyTransition(order, null, Event.PAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(machine.getAvailableEvents(null, OrderEntity::getState)).isEmpty();
        assertThat(machine.getAvailableEvents(order, null)).isEmpty();
    }
}
