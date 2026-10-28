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
        DRAFT, SUBMITTED, APPROVED, REJECTED, CLOSED
    }

    enum Event {
        SUBMIT, APPROVE, REJECT, ADD_COMMENT, CANCEL, RISK_CHECK
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
    @DisplayName("测试四大跃迁拓扑完整集成构建与图例导出")
    void testFourTopologiesAssemblyAndExport() {
        StateMachineBuilder<State, Event, Integer> builder = StateMachineBuilderFactory.create();

        // 1. 外部跃迁
        builder.externalTransition()
                .from(State.DRAFT).to(State.SUBMITTED).on(Event.SUBMIT);

        // 2. 内部自跃迁
        builder.internalTransition()
                .from(State.SUBMITTED).on(Event.ADD_COMMENT);

        // 3. 多源汇聚跃迁
        builder.multiFromTransition()
                .from(State.DRAFT, State.SUBMITTED)
                .to(State.CLOSED)
                .on(Event.CANCEL);

        // 4. Choice 动态分支跃迁
        builder.choiceTransition()
                .from(State.SUBMITTED)
                .on(Event.RISK_CHECK)
                .when(score -> score >= 80).to(State.APPROVED)
                .when(score -> score < 50).to(State.REJECTED)
                .defaultTo(State.SUBMITTED);

        StateMachine<State, Event, Integer> machine = builder.build("FOUR_TOPOLOGY_MACHINE");
        assertThat(machine.getMachineId()).isEqualTo("FOUR_TOPOLOGY_MACHINE");

        // 验证状态机执行行为
        assertThat(machine.fireEvent(State.DRAFT, Event.SUBMIT, 0)).isEqualTo(State.SUBMITTED);
        assertThat(machine.fireEvent(State.SUBMITTED, Event.ADD_COMMENT, 0)).isEqualTo(State.SUBMITTED);
        assertThat(machine.fireEvent(State.DRAFT, Event.CANCEL, 0)).isEqualTo(State.CLOSED);
        assertThat(machine.fireEvent(State.SUBMITTED, Event.CANCEL, 0)).isEqualTo(State.CLOSED);

        // 验证 Choice 路由
        assertThat(machine.fireEvent(State.SUBMITTED, Event.RISK_CHECK, 95)).isEqualTo(State.APPROVED);
        assertThat(machine.fireEvent(State.SUBMITTED, Event.RISK_CHECK, 20)).isEqualTo(State.REJECTED);
        assertThat(machine.fireEvent(State.SUBMITTED, Event.RISK_CHECK, 60)).isEqualTo(State.SUBMITTED);

        // 验证 Mermaid 导出
        String mermaid = machine.generateMermaid();
        assertThat(mermaid)
                .startsWith("stateDiagram-v2")
                .contains("DRAFT --> SUBMITTED : SUBMIT")
                .contains("SUBMITTED --> SUBMITTED : ADD_COMMENT")
                .contains("DRAFT --> CLOSED : CANCEL")
                .contains("SUBMITTED --> CLOSED : CANCEL")
                .contains("SUBMITTED --> APPROVED : RISK_CHECK [choice]")
                .contains("SUBMITTED --> REJECTED : RISK_CHECK [choice]")
                .contains("SUBMITTED --> SUBMITTED : RISK_CHECK [default]");

        // 验证 PlantUML 导出
        String plantUml = machine.generatePlantUML();
        assertThat(plantUml)
                .startsWith("@startuml")
                .endsWith("@enduml")
                .contains("DRAFT --> SUBMITTED : SUBMIT")
                .contains("SUBMITTED --> SUBMITTED : ADD_COMMENT")
                .contains("DRAFT --> CLOSED : CANCEL")
                .contains("SUBMITTED --> CLOSED : CANCEL")
                .contains("SUBMITTED --> APPROVED : RISK_CHECK [choice]")
                .contains("SUBMITTED --> REJECTED : RISK_CHECK [choice]")
                .contains("SUBMITTED --> SUBMITTED : RISK_CHECK [default]");
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
