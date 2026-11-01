package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionAuditEvent;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 专职状态流转轨迹审计监听器 SPI 单元测试
 *
 * @author Ateng
 * @since 2026-11-01
 */
class StateTransitionAuditListenerTest {

    enum OrderState {
        WAIT_PAY, PAID, CANCELLED
    }

    enum OrderEvent {
        PAY, CANCEL
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("测试状态流转成功时触发审计监听器并捕获全量上下文与 TraceId")
    void testAuditListenerOnSuccessfulTransition() {
        MDC.put("traceId", "TRACE-UUID-99999");

        List<TransitionAuditEvent<OrderState, OrderEvent, String>> capturedEvents = new ArrayList<>();
        StateTransitionAuditListener<OrderState, OrderEvent, String> listener = capturedEvents::add;

        StateMachineBuilder<OrderState, OrderEvent, String> builder = StateMachineBuilderFactory.create();
        builder.auditListener(listener);
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY);

        StateMachine<OrderState, OrderEvent, String> machine = builder.build("AUDIT_SUCCESS_MACHINE");

        OrderState target = machine.fireEvent(OrderState.WAIT_PAY, OrderEvent.PAY, "ctx-order-001");
        assertThat(target).isEqualTo(OrderState.PAID);

        assertThat(capturedEvents).hasSize(1);
        TransitionAuditEvent<OrderState, OrderEvent, String> event = capturedEvents.get(0);
        assertThat(event.getMachineId()).isEqualTo("AUDIT_SUCCESS_MACHINE");
        assertThat(event.getFromState()).isEqualTo(OrderState.WAIT_PAY);
        assertThat(event.getToState()).isEqualTo(OrderState.PAID);
        assertThat(event.getEvent()).isEqualTo(OrderEvent.PAY);
        assertThat(event.getContext()).isEqualTo("ctx-order-001");
        assertThat(event.getTraceId()).isEqualTo("TRACE-UUID-99999");
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.getCause()).isNull();
        assertThat(event.getElapsed()).isNotNull();
        assertThat(event.toString()).contains("AUDIT_SUCCESS_MACHINE", "WAIT_PAY", "PAID", "TRACE-UUID-99999");
    }

    @Test
    @DisplayName("测试状态流转异常时触发审计监听器并捕获因果异常")
    void testAuditListenerOnFailedTransition() {
        List<TransitionAuditEvent<OrderState, OrderEvent, Void>> capturedEvents = new ArrayList<>();
        StateTransitionAuditListener<OrderState, OrderEvent, Void> listener = capturedEvents::add;

        StateMachineBuilder<OrderState, OrderEvent, Void> builder = StateMachineBuilderFactory.create();
        builder.auditListener(listener);
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY);

        StateMachine<OrderState, OrderEvent, Void> machine = builder.build("AUDIT_FAIL_MACHINE");

        // 触发不存在的跃迁路由
        assertThatThrownBy(() -> machine.fireEvent(OrderState.PAID, OrderEvent.CANCEL, null))
                .isInstanceOf(StateMachineException.class);

        assertThat(capturedEvents).hasSize(1);
        TransitionAuditEvent<OrderState, OrderEvent, Void> event = capturedEvents.get(0);
        assertThat(event.getMachineId()).isEqualTo("AUDIT_FAIL_MACHINE");
        assertThat(event.getFromState()).isEqualTo(OrderState.PAID);
        assertThat(event.getToState()).isNull();
        assertThat(event.getEvent()).isEqualTo(OrderEvent.CANCEL);
        assertThat(event.isSuccess()).isFalse();
        assertThat(event.getCause()).isInstanceOf(StateMachineException.class);
    }

    @Test
    @DisplayName("测试静默模式 fireEventQuietly 触发审计监听器")
    void testAuditListenerOnQuietTransition() {
        List<TransitionAuditEvent<OrderState, OrderEvent, Void>> capturedEvents = new ArrayList<>();
        StateTransitionAuditListener<OrderState, OrderEvent, Void> listener = capturedEvents::add;

        StateMachineBuilder<OrderState, OrderEvent, Void> builder = StateMachineBuilderFactory.create();
        builder.auditListener(listener);
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY);

        StateMachine<OrderState, OrderEvent, Void> machine = builder.build("AUDIT_QUIET_MACHINE");

        TransitionResult<OrderState> result = machine.fireEventQuietly(OrderState.PAID, OrderEvent.CANCEL, null);
        assertThat(result.isAccepted()).isFalse();

        assertThat(capturedEvents).hasSize(1);
        TransitionAuditEvent<OrderState, OrderEvent, Void> event = capturedEvents.get(0);
        assertThat(event.isSuccess()).isFalse();
        assertThat(event.getCause()).isNotNull();
    }

    @Test
    @DisplayName("测试幂等命中时触发审计监听器 (success=true, fromState=toState)")
    void testAuditListenerOnIdempotentHit() {
        List<TransitionAuditEvent<OrderState, OrderEvent, Void>> capturedEvents = new ArrayList<>();
        StateTransitionAuditListener<OrderState, OrderEvent, Void> listener = capturedEvents::add;

        StateMachineBuilder<OrderState, OrderEvent, Void> builder = StateMachineBuilderFactory.create();
        builder.auditListener(listener);
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .idempotent();

        StateMachine<OrderState, OrderEvent, Void> machine = builder.build("AUDIT_IDEMP_MACHINE");

        // 实体当前已是 PAID，重复到达 PAY 事件
        OrderState target = machine.fireEvent(OrderState.PAID, OrderEvent.PAY, null);
        assertThat(target).isEqualTo(OrderState.PAID);

        assertThat(capturedEvents).hasSize(1);
        TransitionAuditEvent<OrderState, OrderEvent, Void> event = capturedEvents.get(0);
        assertThat(event.getFromState()).isEqualTo(OrderState.PAID);
        assertThat(event.getToState()).isEqualTo(OrderState.PAID);
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.getCause()).isNull();
    }

    @Test
    @DisplayName("测试审计监听器自身抛出异常绝不污染主业务逻辑 (Exception Isolation)")
    void testAuditListenerExceptionIsolation() {
        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        StateMachineBuilder<OrderState, OrderEvent, Void> builder = StateMachineBuilderFactory.create();
        builder.auditListener(event -> {
            throw new RuntimeException("审计日志中间件连接超时，写入失败");
        });

        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .perform((from, to, event, ctx) -> actionExecuted.set(true));

        StateMachine<OrderState, OrderEvent, Void> machine = builder.build("AUDIT_ISOLATION_MACHINE");

        // 监听器异常被内部安全吸收，业务流转不受影响
        OrderState target = machine.fireEvent(OrderState.WAIT_PAY, OrderEvent.PAY, null);
        assertThat(target).isEqualTo(OrderState.PAID);
        assertThat(actionExecuted.get()).isTrue();
    }

    @Test
    @DisplayName("测试动态追加审计监听器与查重保护")
    void testDynamicAddAuditListener() {
        StateMachineBuilder<OrderState, OrderEvent, Void> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY);

        StateMachine<OrderState, OrderEvent, Void> machine = builder.build("DYNAMIC_AUDIT_MACHINE");
        DefaultStateMachine<OrderState, OrderEvent, Void> defaultMachine = (DefaultStateMachine<OrderState, OrderEvent, Void>) machine;

        List<TransitionAuditEvent<OrderState, OrderEvent, Void>> list = new ArrayList<>();
        StateTransitionAuditListener<OrderState, OrderEvent, Void> listener = list::add;

        defaultMachine.addAuditListener(listener);
        defaultMachine.addAuditListener(listener); // 重复添加自动去重
        defaultMachine.addAuditListener(null);

        assertThat(defaultMachine.getAuditListeners()).hasSize(1);

        machine.fireEvent(OrderState.WAIT_PAY, OrderEvent.PAY, null);
        assertThat(list).hasSize(1);
    }
}
