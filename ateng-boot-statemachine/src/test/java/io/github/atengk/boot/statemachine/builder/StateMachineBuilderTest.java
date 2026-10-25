package io.github.atengk.boot.statemachine.builder;

import io.github.atengk.boot.statemachine.core.StateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机构建器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class StateMachineBuilderTest {

    enum State {
        DRAFT, SUBMITTED, APPROVED, REJECTED
    }

    enum Event {
        SUBMIT, APPROVE, REJECT, ADD_COMMENT
    }

    @Test
    @DisplayName("测试构建器完整组装并动态导出图例")
    void testBuildAndExport() {
        StateMachineBuilder<State, Event, Object> builder = StateMachineBuilderFactory.create();

        builder.externalTransition()
                .from(State.DRAFT).to(State.SUBMITTED).on(Event.SUBMIT);

        builder.externalTransition()
                .from(State.SUBMITTED).to(State.APPROVED).on(Event.APPROVE);

        builder.externalTransition()
                .from(State.SUBMITTED).to(State.REJECTED).on(Event.REJECT);

        builder.internalTransition()
                .from(State.SUBMITTED).on(Event.ADD_COMMENT);

        StateMachine<State, Event, Object> machine = builder.build("APPROVAL_FLOW");
        assertThat(machine.getMachineId()).isEqualTo("APPROVAL_FLOW");

        String mermaid = machine.generateMermaid();
        assertThat(mermaid)
                .startsWith("stateDiagram-v2")
                .contains("DRAFT --> SUBMITTED : SUBMIT")
                .contains("SUBMITTED --> APPROVED : APPROVE")
                .contains("SUBMITTED --> REJECTED : REJECT")
                .contains("SUBMITTED --> SUBMITTED : ADD_COMMENT");

        String plantUml = machine.generatePlantUML();
        assertThat(plantUml)
                .startsWith("@startuml")
                .endsWith("@enduml")
                .contains("DRAFT --> SUBMITTED : SUBMIT")
                .contains("SUBMITTED --> SUBMITTED : ADD_COMMENT");
    }

    @Test
    @DisplayName("测试空 machineId 校验拦截")
    void testBlankMachineId() {
        StateMachineBuilder<State, Event, Object> builder = StateMachineBuilderFactory.create();
        assertThatThrownBy(() -> builder.build(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("machineId must not be blank");

        assertThatThrownBy(() -> builder.build(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("machineId must not be blank");
    }

    @Test
    @DisplayName("测试未完整配置的跃迁在构建期被拦截")
    void testIncompleteTransition() {
        StateMachineBuilder<State, Event, Object> builder = StateMachineBuilderFactory.create();
        builder.externalTransition().from(State.DRAFT); // 未调用 to 和 on

        assertThatThrownBy(() -> builder.build("INCOMPLETE_MACHINE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incomplete external transition configuration");
    }
}
