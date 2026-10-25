package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机核心契约骨架测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class StateMachineSkeletonTest {

    static class Order {
        private String status;

        public Order(String status) {
            this.status = status;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    static class DummyStateMachine implements StateMachine<String, String, Object> {

        @Override
        public String getMachineId() {
            return "ORDER_MACHINE";
        }

        @Override
        public String fireEvent(String sourceState, String event, Object context) {
            if ("INIT".equals(sourceState) && "PAY".equals(event)) {
                return "PAID";
            }
            throw new IllegalArgumentException("Invalid transition");
        }

        @Override
        public TransitionResult<String> fireEventQuietly(String sourceState, String event, Object context) {
            if ("INIT".equals(sourceState) && "PAY".equals(event)) {
                return TransitionResult.success(sourceState, "PAID", event);
            }
            return TransitionResult.failure(sourceState, event, "Invalid transition");
        }

        @Override
        public String generateMermaid() {
            return "stateDiagram-v2\n[*] --> INIT\nINIT --> PAID : PAY";
        }

        @Override
        public String generatePlantUML() {
            return "@startuml\n[*] --> INIT\nINIT --> PAID : PAY\n@enduml";
        }
    }

    @Test
    @DisplayName("测试状态机基础接口契约与图例导出")
    void testBasicContract() {
        DummyStateMachine machine = new DummyStateMachine();
        assertThat(machine.getMachineId()).isEqualTo("ORDER_MACHINE");

        String target = machine.fireEvent("INIT", "PAY", null);
        assertThat(target).isEqualTo("PAID");

        TransitionResult<String> successResult = machine.fireEventQuietly("INIT", "PAY", null);
        assertThat(successResult.isAccepted()).isTrue();
        assertThat(successResult.getTargetState()).isEqualTo("PAID");

        TransitionResult<String> failResult = machine.fireEventQuietly("PAID", "PAY", null);
        assertThat(failResult.isAccepted()).isFalse();

        assertThat(machine.generateMermaid()).contains("stateDiagram-v2");
        assertThat(machine.generatePlantUML()).contains("@startuml");
    }

    @Test
    @DisplayName("测试实体 Lambda 门面自动回写状态")
    void testEntityLambdaAccessor() {
        DummyStateMachine machine = new DummyStateMachine();
        Order order = new Order("INIT");

        String target = machine.fireEvent(order, Order::getStatus, Order::setStatus, "PAY", null);
        assertThat(target).isEqualTo("PAID");
        assertThat(order.getStatus()).isEqualTo("PAID");

        // 验证空防御
        assertThatThrownBy(() -> machine.fireEvent(null, Order::getStatus, Order::setStatus, "PAY", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target entity must not be null");

        assertThatThrownBy(() -> machine.fireEvent(order, null, Order::setStatus, "PAY", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Getter and setter must not be null");
    }
}
