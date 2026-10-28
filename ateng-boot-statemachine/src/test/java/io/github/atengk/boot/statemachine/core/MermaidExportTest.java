package io.github.atengk.boot.statemachine.core;

import io.github.atengk.boot.statemachine.builder.StateMachineBuilder;
import io.github.atengk.boot.statemachine.builder.StateMachineBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机 Mermaid 与 PlantUML 图例动态导出单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class MermaidExportTest {

    enum State {
        INIT, PROCESSING, COMPLETED, FAILED, RETRYING
    }

    enum Event {
        START, PROCESS, SUCCESS, FAIL, RETRY
    }

    @Test
    @DisplayName("测试状态机全拓扑动态导出 Mermaid 与 PlantUML 图例语法")
    void testDiagramExport() {
        StateMachineBuilder<State, Event, Integer> builder = StateMachineBuilderFactory.create();

        // 外部跃迁
        builder.externalTransition().from(State.INIT).to(State.PROCESSING).on(Event.START);

        // 内部跃迁
        builder.internalTransition().from(State.PROCESSING).on(Event.PROCESS);

        // Choice 分支
        builder.choiceTransition()
                .from(State.PROCESSING)
                .on(Event.SUCCESS)
                .when(score -> score >= 60).to(State.COMPLETED)
                .defaultTo(State.RETRYING);

        // 多源汇聚
        builder.multiFromTransition()
                .from(State.PROCESSING, State.RETRYING)
                .to(State.FAILED)
                .on(Event.FAIL);

        StateMachine<State, Event, Integer> machine = builder.build("EXPORT_DIAGRAM_MACHINE");

        // 验证 Mermaid 语法 (stateDiagram-v2)
        String mermaid = machine.generateMermaid();
        assertThat(mermaid)
                .startsWith("stateDiagram-v2")
                .contains("INIT --> PROCESSING : START")
                .contains("PROCESSING --> PROCESSING : PROCESS")
                .contains("PROCESSING --> COMPLETED : SUCCESS [choice]")
                .contains("PROCESSING --> RETRYING : SUCCESS [default]")
                .contains("PROCESSING --> FAILED : FAIL")
                .contains("RETRYING --> FAILED : FAIL");

        // 验证 PlantUML 语法 (@startuml ... @enduml)
        String plantUml = machine.generatePlantUML();
        assertThat(plantUml)
                .startsWith("@startuml")
                .endsWith("@enduml")
                .contains("INIT --> PROCESSING : START")
                .contains("PROCESSING --> PROCESSING : PROCESS")
                .contains("PROCESSING --> COMPLETED : SUCCESS [choice]")
                .contains("PROCESSING --> RETRYING : SUCCESS [default]")
                .contains("PROCESSING --> FAILED : FAIL")
                .contains("RETRYING --> FAILED : FAIL");
    }
}
