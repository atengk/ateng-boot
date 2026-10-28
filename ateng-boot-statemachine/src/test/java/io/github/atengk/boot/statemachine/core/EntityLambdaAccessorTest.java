package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实体状态零侵入 Lambda 访问门面单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class EntityLambdaAccessorTest {

    enum OrderStatus {
        WAIT_PAY, PAID, DELIVERED, CLOSED
    }

    enum OrderEvent {
        PAY, SHIP, CANCEL
    }

    static class OrderDO {
        private final Long id;
        private OrderStatus status;

        public OrderDO(Long id, OrderStatus status) {
            this.id = id;
            this.status = status;
        }

        public Long getId() {
            return id;
        }

        public OrderStatus getStatus() {
            return status;
        }

        public void setStatus(OrderStatus status) {
            this.status = status;
        }
    }

    private StateMachine<OrderStatus, OrderEvent, String> createTestMachine() {
        StateMachineBuilder<OrderStatus, OrderEvent, String> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderStatus.WAIT_PAY)
                .to(OrderStatus.PAID)
                .on(OrderEvent.PAY)
                .when(ctx -> !"BLOCK".equals(ctx));

        builder.externalTransition()
                .from(OrderStatus.PAID)
                .to(OrderStatus.DELIVERED)
                .on(OrderEvent.SHIP);

        return builder.build("ORDER_LAMBDA_MACHINE");
    }

    @Test
    @DisplayName("测试 Fail-Fast 模式下实体状态自动提取与成功回写")
    void testFireEventEntitySuccess() {
        StateMachine<OrderStatus, OrderEvent, String> machine = createTestMachine();
        OrderDO order = new OrderDO(1001L, OrderStatus.WAIT_PAY);

        OrderStatus target = machine.fireEvent(order, OrderDO::getStatus, OrderDO::setStatus, OrderEvent.PAY, "NORMAL");

        assertThat(target).isEqualTo(OrderStatus.PAID);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("测试 Fail-Fast 模式下跃迁失败时保持实体状态不变")
    void testFireEventEntityFailedRollback() {
        StateMachine<OrderStatus, OrderEvent, String> machine = createTestMachine();
        OrderDO order = new OrderDO(1002L, OrderStatus.WAIT_PAY);

        // 1. 前置条件不通过
        assertThatThrownBy(() -> machine.fireEvent(order, OrderDO::getStatus, OrderDO::setStatus, OrderEvent.PAY, "BLOCK"))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.CONDITION_FAILED.getCode());
                });
        assertThat(order.getStatus()).isEqualTo(OrderStatus.WAIT_PAY);

        // 2. 无匹配路由
        assertThatThrownBy(() -> machine.fireEvent(order, OrderDO::getStatus, OrderDO::setStatus, OrderEvent.SHIP, "NORMAL"))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.TRANSITION_NOT_FOUND.getCode());
                });
        assertThat(order.getStatus()).isEqualTo(OrderStatus.WAIT_PAY);
    }

    @Test
    @DisplayName("测试静默探测模式下实体状态自动提取与成功回写")
    void testFireEventQuietlyEntitySuccess() {
        StateMachine<OrderStatus, OrderEvent, String> machine = createTestMachine();
        OrderDO order = new OrderDO(1003L, OrderStatus.WAIT_PAY);

        TransitionResult<OrderStatus> result = machine.fireEventQuietly(
                order, OrderDO::getStatus, OrderDO::setStatus, OrderEvent.PAY, "NORMAL"
        );

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.getSourceState()).isEqualTo(OrderStatus.WAIT_PAY);
        assertThat(result.getTargetState()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("测试静默探测模式下跃迁失败时不回写实体状态")
    void testFireEventQuietlyEntityFailedNoWriteback() {
        StateMachine<OrderStatus, OrderEvent, String> machine = createTestMachine();
        OrderDO order = new OrderDO(1004L, OrderStatus.WAIT_PAY);

        TransitionResult<OrderStatus> result = machine.fireEventQuietly(
                order, OrderDO::getStatus, OrderDO::setStatus, OrderEvent.PAY, "BLOCK"
        );

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getFailureReason()).contains("前置条件卫语句校验不通过");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.WAIT_PAY);
    }

    @Test
    @DisplayName("测试参数防御拦截: null 实体、getter 与 setter")
    void testDefensiveValidation() {
        StateMachine<OrderStatus, OrderEvent, String> machine = createTestMachine();
        OrderDO order = new OrderDO(1005L, OrderStatus.WAIT_PAY);

        // 1. null entity
        assertThatThrownBy(() -> machine.fireEvent(null, OrderDO::getStatus, OrderDO::setStatus, OrderEvent.PAY, "NORMAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target entity must not be null");

        TransitionResult<OrderStatus> quietResult = machine.fireEventQuietly(
                null, OrderDO::getStatus, OrderDO::setStatus, OrderEvent.PAY, "NORMAL"
        );
        assertThat(quietResult.isAccepted()).isFalse();
        assertThat(quietResult.getFailureReason()).contains("Target entity must not be null");

        // 2. null getter
        assertThatThrownBy(() -> machine.fireEvent(order, null, OrderDO::setStatus, OrderEvent.PAY, "NORMAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Getter and setter must not be null");

        assertThatThrownBy(() -> machine.fireEventQuietly(order, null, OrderDO::setStatus, OrderEvent.PAY, "NORMAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Getter and setter must not be null");

        // 3. null setter
        assertThatThrownBy(() -> machine.fireEvent(order, OrderDO::getStatus, null, OrderEvent.PAY, "NORMAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Getter and setter must not be null");

        assertThatThrownBy(() -> machine.fireEventQuietly(order, OrderDO::getStatus, null, OrderEvent.PAY, "NORMAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Getter and setter must not be null");
    }
}
