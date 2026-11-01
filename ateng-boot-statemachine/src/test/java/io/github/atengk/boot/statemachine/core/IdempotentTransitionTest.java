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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明式终态幂等策略与重复事件放行治理单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
class IdempotentTransitionTest {

    enum OrderState {
        WAIT_PAY, PAID, CLOSED, DELIVERED
    }

    enum OrderEvent {
        PAY, CANCEL, DELIVER
    }

    @Test
    @DisplayName("测试标准外部跃迁声明幂等放行：重复投递返回终态且免于重复执行动作")
    void testExternalTransitionIdempotency() {
        AtomicInteger actionExecutionCount = new AtomicInteger(0);

        StateMachineBuilder<OrderState, OrderEvent, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .idempotent(true)
                .perform((from, to, event, ctx) -> actionExecutionCount.incrementAndGet());

        StateMachine<OrderState, OrderEvent, String> machine = builder.build("IDEMPOTENT_EXTERNAL_MACHINE");

        // 1. 首次触发跃迁：WAIT_PAY -> PAID
        OrderState firstResult = machine.fireEvent(OrderState.WAIT_PAY, OrderEvent.PAY, "ORDER_1001");
        assertThat(firstResult).isEqualTo(OrderState.PAID);
        assertThat(actionExecutionCount.get()).isEqualTo(1);

        // 2. 二次触发重复投递（Webhook 重试）：当前已处于 PAID，收到 PAY
        OrderState repeatResult = machine.fireEvent(OrderState.PAID, OrderEvent.PAY, "ORDER_1001");
        assertThat(repeatResult).isEqualTo(OrderState.PAID);
        // 关键断言：动作免于二次重复执行
        assertThat(actionExecutionCount.get()).isEqualTo(1);

        // 3. 静默模式探测重复投递：返回 accepted=true 且 isIdempotent()=true
        TransitionResult<OrderState> quietResult = machine.fireEventQuietly(OrderState.PAID, OrderEvent.PAY, "ORDER_1001");
        assertThat(quietResult.isAccepted()).isTrue();
        assertThat(quietResult.isIdempotent()).isTrue();
        assertThat(quietResult.getSourceState()).isEqualTo(OrderState.PAID);
        assertThat(quietResult.getTargetState()).isEqualTo(OrderState.PAID);
        assertThat(actionExecutionCount.get()).isEqualTo(1);

        // 4. 纯只读探测门面验证：canFire 返回 true，verifyTransition 返回 idempotent
        assertThat(machine.canFire(OrderState.PAID, OrderEvent.PAY)).isTrue();
        TransitionResult<OrderState> probe = machine.verifyTransition(OrderState.PAID, OrderEvent.PAY);
        assertThat(probe.isAccepted()).isTrue();
        assertThat(probe.isIdempotent()).isTrue();

        // 5. 可用事件查询：getAvailableEvents 包含幂等可用事件 PAY
        Set<OrderEvent> availableEvents = machine.getAvailableEvents(OrderState.PAID);
        assertThat(availableEvents).contains(OrderEvent.PAY);
    }

    @Test
    @DisplayName("测试未声明幂等的跃迁在终态收到重复事件时坚决抛出 TRANSITION_NOT_FOUND")
    void testNonIdempotentEventFailsFast() {
        StateMachineBuilder<OrderState, OrderEvent, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY); // 默认 idempotent = false

        StateMachine<OrderState, OrderEvent, String> machine = builder.build("NON_IDEMPOTENT_MACHINE");

        // 1. 处于 PAID 时收到 PAY，未开启幂等放行，必须抛出 TRANSITION_NOT_FOUND
        assertThatThrownBy(() -> machine.fireEvent(OrderState.PAID, OrderEvent.PAY, "ORDER_1002"))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.TRANSITION_NOT_FOUND.getCode());
                });

        // 2. 静默模式返回失败
        TransitionResult<OrderState> quietResult = machine.fireEventQuietly(OrderState.PAID, OrderEvent.PAY, "ORDER_1002");
        assertThat(quietResult.isAccepted()).isFalse();
        assertThat(quietResult.isIdempotent()).isFalse();
        assertThat(quietResult.getFailureReason()).contains("未找到");

        // 3. canFire 返回 false
        assertThat(machine.canFire(OrderState.PAID, OrderEvent.PAY)).isFalse();
    }

    @Test
    @DisplayName("测试在非目标终态收到不匹配事件时坚决抛出 TRANSITION_NOT_FOUND")
    void testIdempotentEventOnIrrelevantStateFails() {
        StateMachineBuilder<OrderState, OrderEvent, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .idempotent(true);

        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.CLOSED)
                .on(OrderEvent.CANCEL)
                .idempotent(true);

        StateMachine<OrderState, OrderEvent, String> machine = builder.build("IRRELEVANT_STATE_MACHINE");

        // CLOSED 状态收到 PAY 事件：PAY 并非指向 CLOSED 的幂等事件，必须报错
        assertThatThrownBy(() -> machine.fireEvent(OrderState.CLOSED, OrderEvent.PAY, "DATA"))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.TRANSITION_NOT_FOUND.getCode());
                });
    }

    @Test
    @DisplayName("测试全局构建器与四大构建器全面支持 idempotent DSL")
    void testAllBuildersSupportIdempotentDsl() {
        StateMachineBuilder<OrderState, OrderEvent, Integer> builder = StateMachineBuilderFactory.create();

        // 1. 全局默认开启幂等
        builder.idempotent();

        // 2. MultiFrom 跃迁
        builder.multiFromTransition()
                .from(OrderState.WAIT_PAY, OrderState.PAID)
                .to(OrderState.DELIVERED)
                .on(OrderEvent.DELIVER);

        // 3. Choice 跃迁
        builder.choiceTransition()
                .from(OrderState.WAIT_PAY)
                .on(OrderEvent.CANCEL)
                .when(ctx -> ctx != null && ctx > 0).to(OrderState.CLOSED)
                .defaultTo(OrderState.WAIT_PAY)
                .idempotent();

        StateMachine<OrderState, OrderEvent, Integer> machine = builder.build("ALL_BUILDERS_IDEMP_MACHINE");

        // 验证 MultiFrom 目标状态 DELIVERED 接收 DELIVER 幂等放行
        OrderState multiFromResult = machine.fireEvent(OrderState.DELIVERED, OrderEvent.DELIVER, 0);
        assertThat(multiFromResult).isEqualTo(OrderState.DELIVERED);

        // 验证 Choice 分支目标 CLOSED 接收 CANCEL 幂等放行
        OrderState choiceResult = machine.fireEvent(OrderState.CLOSED, OrderEvent.CANCEL, 1);
        assertThat(choiceResult).isEqualTo(OrderState.CLOSED);
    }

    @Test
    @DisplayName("测试 CAS 乐观锁流转协同终态幂等放行：跳过持久化 CAS 与动作")
    void testCasTemplateWithIdempotentHit() {
        AtomicInteger casCount = new AtomicInteger(0);
        AtomicInteger actionCount = new AtomicInteger(0);

        StateMachineBuilder<OrderState, OrderEvent, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .idempotent(true)
                .perform((from, to, event, ctx) -> actionCount.incrementAndGet());

        StateMachine<OrderState, OrderEvent, String> machine = builder.build("CAS_IDEMP_MACHINE");

        class OrderEntity {
            OrderState state;
            OrderEntity(OrderState state) { this.state = state; }
        }

        // 当前实体已处于终态 PAID
        OrderEntity entity = new OrderEntity(OrderState.PAID);

        // 1. Fail-Fast 模式
        OrderState target = machine.fireEventWithCas(
                entity,
                e -> e.state,
                (e, s) -> e.state = s,
                OrderEvent.PAY,
                "ORDER_999",
                (ent, from, to) -> casCount.incrementAndGet()
        );

        assertThat(target).isEqualTo(OrderState.PAID);
        // 关键断言：CAS 持久化更新与 Action 动作均被免除
        assertThat(casCount.get()).isEqualTo(0);
        assertThat(actionCount.get()).isEqualTo(0);
        assertThat(entity.state).isEqualTo(OrderState.PAID);

        // 2. 静默探测模式
        TransitionResult<OrderState> quietResult = machine.fireEventWithCasQuietly(
                entity,
                e -> e.state,
                (e, s) -> e.state = s,
                OrderEvent.PAY,
                "ORDER_999",
                (ent, from, to) -> casCount.incrementAndGet()
        );
        assertThat(quietResult.isAccepted()).isTrue();
        assertThat(quietResult.isIdempotent()).isTrue();
        assertThat(casCount.get()).isEqualTo(0);
        assertThat(actionCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试幂等命中时切面拦截器生命周期正常闭环通知")
    void testInterceptorOnIdempotentHit() {
        List<String> eventsList = new ArrayList<>();

        StateMachineBuilder<OrderState, OrderEvent, String> builder = StateMachineBuilderFactory.create();
        builder.interceptor(new StateMachineInterceptor<OrderState, OrderEvent, String>() {
            @Override
            public void beforeTransition(OrderState sourceState, OrderEvent event, String context) {
                eventsList.add("BEFORE:" + sourceState);
            }

            @Override
            public void afterTransition(OrderState sourceState, OrderState targetState, OrderEvent event, String context, java.time.Duration elapsed) {
                eventsList.add("AFTER:" + sourceState + "->" + targetState);
            }
        });

        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .idempotent();

        StateMachine<OrderState, OrderEvent, String> machine = builder.build("INTERCEPTOR_IDEMP_MACHINE");

        // 处于 PAID 收到 PAY，幂等命中
        machine.fireEvent(OrderState.PAID, OrderEvent.PAY, "TEST");

        assertThat(eventsList).containsExactly("BEFORE:PAID", "AFTER:PAID->PAID");
    }
}
