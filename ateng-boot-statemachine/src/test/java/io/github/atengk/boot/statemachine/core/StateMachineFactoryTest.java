package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全局状态机工厂单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class StateMachineFactoryTest {

    enum OrderState {
        CREATED, PAID
    }

    enum OrderEvent {
        PAY
    }

    @AfterEach
    void tearDown() {
        StateMachineFactory.clear();
    }

    private StateMachine<OrderState, OrderEvent, Object> createMachine(String machineId) {
        StateMachineBuilder<OrderState, OrderEvent, Object> builder = StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderState.CREATED)
                .to(OrderState.PAID)
                .on(OrderEvent.PAY);
        return builder.build(machineId);
    }

    @Test
    @DisplayName("测试状态机注册、获取与包含判断")
    void testRegisterAndGet() {
        StateMachine<OrderState, OrderEvent, Object> machine = createMachine("ORDER_MACHINE");
        StateMachineFactory.register(machine);

        assertThat(StateMachineFactory.contains("ORDER_MACHINE")).isTrue();

        StateMachine<OrderState, OrderEvent, Object> retrieved = StateMachineFactory.get("ORDER_MACHINE");
        assertThat(retrieved).isSameAs(machine);
        assertThat(retrieved.getMachineId()).isEqualTo("ORDER_MACHINE");
    }

    @Test
    @DisplayName("测试重复注册相同 machineId 抛出 DUPLICATE_MACHINE_ID 异常")
    void testDuplicateRegistrationConflict() {
        StateMachine<OrderState, OrderEvent, Object> machine1 = createMachine("CONFLICT_MACHINE");
        StateMachine<OrderState, OrderEvent, Object> machine2 = createMachine("CONFLICT_MACHINE");

        StateMachineFactory.register(machine1);

        assertThatThrownBy(() -> StateMachineFactory.register(machine2))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.DUPLICATE_MACHINE_ID.getCode());
                    assertThat(smEx.getMessage()).contains("重复注册冲突");
                });
    }

    @Test
    @DisplayName("测试获取未注册的状态机抛出异常")
    void testGetNonExistentMachine() {
        assertThatThrownBy(() -> StateMachineFactory.get("NON_EXISTENT"))
                .isInstanceOf(StateMachineException.class)
                .satisfies(ex -> {
                    StateMachineException smEx = (StateMachineException) ex;
                    assertThat(smEx.getCode()).isEqualTo(StateMachineResultCode.MACHINE_NOT_FOUND.getCode());
                    assertThat(smEx.getMessage()).contains("未找到标识为 [NON_EXISTENT] 的状态机实例");
                });
    }

    @Test
    @DisplayName("测试状态机注销与清理")
    void testUnregisterAndClear() {
        StateMachine<OrderState, OrderEvent, Object> machine = createMachine("TEMP_MACHINE");
        StateMachineFactory.register(machine);
        assertThat(StateMachineFactory.contains("TEMP_MACHINE")).isTrue();

        StateMachine<?, ?, ?> removed = StateMachineFactory.unregister("TEMP_MACHINE");
        assertThat(removed).isSameAs(machine);
        assertThat(StateMachineFactory.contains("TEMP_MACHINE")).isFalse();

        // 重新注册并清理全部
        StateMachineFactory.register(machine);
        StateMachineFactory.clear();
        assertThat(StateMachineFactory.contains("TEMP_MACHINE")).isFalse();
    }

    @Test
    @DisplayName("测试参数防御与空值安全")
    void testDefensiveValidation() {
        assertThatThrownBy(() -> StateMachineFactory.register(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("StateMachine must not be null");

        assertThatThrownBy(() -> StateMachineFactory.get(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("machineId must not be blank");

        assertThatThrownBy(() -> StateMachineFactory.get("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("machineId must not be blank");

        assertThat(StateMachineFactory.contains(null)).isFalse();
        assertThat(StateMachineFactory.unregister(null)).isNull();
    }
}
