package io.github.atengk.boot.statemachine.cas;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机 CAS 乐观锁防重执行模板单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class StateMachineCasTemplateTest {

    enum OrderState {
        WAIT_PAY, PAID, CLOSED
    }

    enum OrderEvent {
        PAY, CANCEL
    }

    static class OrderDO {
        private final Long id;
        private OrderState state;

        public OrderDO(Long id, OrderState state) {
            this.id = id;
            this.state = state;
        }

        public Long getId() {
            return id;
        }

        public OrderState getState() {
            return state;
        }

        public void setState(OrderState state) {
            this.state = state;
        }
    }

    private StateMachine<OrderState, OrderEvent, Boolean> createMachine() {
        StateMachineBuilder<OrderState, OrderEvent, Boolean> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY)
                .when(allow -> allow != null && allow);

        builder.externalTransition()
                .from(OrderState.WAIT_PAY)
                .to(OrderState.CLOSED)
                .on(OrderEvent.CANCEL);

        return builder.build("CAS_ORDER_MACHINE");
    }

    @Test
    @DisplayName("测试 CAS 乐观锁更新成功（受影响行数 = 1）")
    void testCasSuccess() {
        StateMachine<OrderState, OrderEvent, Boolean> machine = createMachine();
        OrderDO order = new OrderDO(101L, OrderState.WAIT_PAY);

        AtomicInteger executedSqlCount = new AtomicInteger(0);

        OrderState target = machine.fireEventWithCas(
                order,
                OrderDO::getState,
                OrderDO::setState,
                OrderEvent.PAY,
                true,
                (entity, source, to) -> {
                    executedSqlCount.incrementAndGet();
                    assertThat(source).isEqualTo(OrderState.WAIT_PAY);
                    assertThat(to).isEqualTo(OrderState.PAID);
                    return 1; // 模拟 UPDATE t_order SET status = 'PAID' WHERE id = 101 AND status = 'WAIT_PAY' 成功
                }
        );

        assertThat(target).isEqualTo(OrderState.PAID);
        assertThat(order.getState()).isEqualTo(OrderState.PAID);
        assertThat(executedSqlCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试 CAS 乐观锁并发冲突拦截（受影响行数 = 0）")
    void testCasConflictFailFast() {
        StateMachine<OrderState, OrderEvent, Boolean> machine = createMachine();
        OrderDO order = new OrderDO(102L, OrderState.WAIT_PAY);

        // 模拟并发更新冲突：行数为 0
        assertThatThrownBy(() -> machine.fireEventWithCas(
                order,
                OrderDO::getState,
                OrderDO::setState,
                OrderEvent.PAY,
                true,
                (entity, source, to) -> 0 // 并发竞争失败
        ))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.CONCURRENT_CAS_CONFLICT.getCode());
                    assertThat(smEx.getSourceState()).isEqualTo(OrderState.WAIT_PAY);
                    assertThat(smEx.getTargetState()).isEqualTo(OrderState.PAID);
                    assertThat(smEx.getMessage()).contains("受影响行数为 0");
                });

        // 验证并发冲突时内存实体状态保持未变更
        assertThat(order.getState()).isEqualTo(OrderState.WAIT_PAY);
    }

    @Test
    @DisplayName("测试静默模式下 CAS 乐观锁成功与冲突结果")
    void testCasQuietly() {
        StateMachine<OrderState, OrderEvent, Boolean> machine = createMachine();

        // 1. CAS 成功
        OrderDO order1 = new OrderDO(103L, OrderState.WAIT_PAY);
        TransitionResult<OrderState> resultSuccess = machine.fireEventWithCasQuietly(
                order1,
                OrderDO::getState,
                OrderDO::setState,
                OrderEvent.PAY,
                true,
                (entity, source, to) -> 1
        );
        assertThat(resultSuccess.isAccepted()).isTrue();
        assertThat(resultSuccess.getTargetState()).isEqualTo(OrderState.PAID);
        assertThat(order1.getState()).isEqualTo(OrderState.PAID);

        // 2. CAS 冲突（受影响行数为 0）
        OrderDO order2 = new OrderDO(104L, OrderState.WAIT_PAY);
        TransitionResult<OrderState> resultConflict = machine.fireEventWithCasQuietly(
                order2,
                OrderDO::getState,
                OrderDO::setState,
                OrderEvent.PAY,
                true,
                (entity, source, to) -> 0
        );
        assertThat(resultConflict.isAccepted()).isFalse();
        assertThat(resultConflict.getFailureReason()).contains("并发 CAS 乐观锁更新冲突");
        assertThat(order2.getState()).isEqualTo(OrderState.WAIT_PAY);
    }

    @Test
    @DisplayName("测试状态跃迁被拦截时绝对不执行下游 CAS 数据库更新")
    void testCasNotExecutedWhenTransitionFails() {
        StateMachine<OrderState, OrderEvent, Boolean> machine = createMachine();
        OrderDO order = new OrderDO(105L, OrderState.WAIT_PAY);

        AtomicBoolean casCalled = new AtomicBoolean(false);

        // 1. Fail-Fast 模式
        assertThatThrownBy(() -> machine.fireEventWithCas(
                order,
                OrderDO::getState,
                OrderDO::setState,
                OrderEvent.PAY,
                false, // 条件不满足
                (entity, source, to) -> {
                    casCalled.set(true);
                    return 1;
                }
        )).isInstanceOf(StateMachineException.class);

        assertThat(casCalled.get()).isFalse();

        // 2. 静默模式
        TransitionResult<OrderState> quietResult = machine.fireEventWithCasQuietly(
                order,
                OrderDO::getState,
                OrderDO::setState,
                OrderEvent.PAY,
                false,
                (entity, source, to) -> {
                    casCalled.set(true);
                    return 1;
                }
        );
        assertThat(quietResult.isAccepted()).isFalse();
        assertThat(casCalled.get()).isFalse();
    }

    @Test
    @DisplayName("测试 CAS 持久化执行异常传递与因果保留")
    void testCasDatabaseException() {
        StateMachine<OrderState, OrderEvent, Boolean> machine = createMachine();
        OrderDO order = new OrderDO(106L, OrderState.WAIT_PAY);

        // Fail-Fast 模式
        assertThatThrownBy(() -> machine.fireEventWithCas(
                order,
                OrderDO::getState,
                OrderDO::setState,
                OrderEvent.PAY,
                true,
                (entity, source, to) -> {
                    throw new RuntimeException("数据库连接池耗尽");
                }
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("数据库连接池耗尽");

        assertThat(order.getState()).isEqualTo(OrderState.WAIT_PAY);

        // 静默模式
        TransitionResult<OrderState> quietResult = machine.fireEventWithCasQuietly(
                order,
                OrderDO::getState,
                OrderDO::setState,
                OrderEvent.PAY,
                true,
                (entity, source, to) -> {
                    throw new RuntimeException("数据库连接池耗尽");
                }
        );
        assertThat(quietResult.isAccepted()).isFalse();
        assertThat(quietResult.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(quietResult.getFailureReason()).contains("执行 CAS 更新时发生异常");
    }

    @Test
    @DisplayName("测试参数防御与空值安全")
    void testDefensiveValidation() {
        StateMachine<OrderState, OrderEvent, Boolean> machine = createMachine();
        OrderDO order = new OrderDO(107L, OrderState.WAIT_PAY);

        // 1. null machine
        assertThatThrownBy(() -> StateMachineCasTemplate.execute(
                null, order, OrderDO::getState, OrderDO::setState, OrderEvent.PAY, true, (e, s, t) -> 1
        )).isInstanceOf(IllegalArgumentException.class);

        // 2. null entity
        assertThatThrownBy(() -> machine.fireEventWithCas(
                null, OrderDO::getState, OrderDO::setState, OrderEvent.PAY, true, (e, s, t) -> 1
        )).isInstanceOf(IllegalArgumentException.class);

        // 3. null getter / setter
        assertThatThrownBy(() -> machine.fireEventWithCas(
                order, null, OrderDO::setState, OrderEvent.PAY, true, (e, s, t) -> 1
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> machine.fireEventWithCas(
                order, OrderDO::getState, null, OrderEvent.PAY, true, (e, s, t) -> 1
        )).isInstanceOf(IllegalArgumentException.class);

        // 4. null casUpdater
        assertThatThrownBy(() -> machine.fireEventWithCas(
                order, OrderDO::getState, OrderDO::setState, OrderEvent.PAY, true, null
        )).isInstanceOf(IllegalArgumentException.class);

        // 5. executeQuietly null 实体与状态机
        TransitionResult<OrderState> quietNullMachine = StateMachineCasTemplate.executeQuietly(
                null, order, OrderDO::getState, OrderDO::setState, OrderEvent.PAY, true, (e, s, t) -> 1
        );
        assertThat(quietNullMachine.isAccepted()).isFalse();

        TransitionResult<OrderState> quietNullEntity = machine.fireEventWithCasQuietly(
                null, OrderDO::getState, OrderDO::setState, OrderEvent.PAY, true, (e, s, t) -> 1
        );
        assertThat(quietNullEntity.isAccepted()).isFalse();
    }
}
