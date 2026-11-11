package io.github.atengk.boot.sample.statemachine.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.core.StateMachineFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 状态机拓扑可视化与工厂内省控制器
 * <p>
 * 提供状态机静态路由拓扑图动态导出（Mermaid stateDiagram-v2 / PlantUML 语法文本生成），
 * 以及基于 StateMachineFactory 全局注册表的机台单例内省探查。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/statemachine/topology")
@RequiredArgsConstructor
public class StateMachineTopologyController {

    private final StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;

    /**
     * 1. 导出动态 Mermaid 状态拓扑图语法文本
     *
     * @return Mermaid stateDiagram-v2 格式源码
     */
    @GetMapping("/mermaid")
    public Result<String> exportMermaid() {
        log.info("[TopologyController] 导出 Mermaid 状态机拓扑图");
        String mermaid = orderStateMachine.generateMermaid();
        return Result.success(mermaid);
    }

    /**
     * 2. 导出动态 PlantUML 状态拓扑图语法文本
     *
     * @return PlantUML @startuml ... @enduml 格式源码
     */
    @GetMapping("/plantuml")
    public Result<String> exportPlantUml() {
        log.info("[TopologyController] 导出 PlantUML 状态机拓扑图");
        String plantUml = orderStateMachine.generatePlantUML();
        return Result.success(plantUml);
    }

    /**
     * 3. 探查 StateMachineFactory 全局单例注册表中所有已注册的机台 ID 列表
     *
     * @return 机台全局标识集合
     */
    @GetMapping("/machines")
    public Result<Set<String>> getRegisteredMachines() {
        log.info("[TopologyController] 探查全局机台单例注册表");
        Set<String> machineIds = StateMachineFactory.getAllMachineIds();
        return Result.success(machineIds);
    }
}
