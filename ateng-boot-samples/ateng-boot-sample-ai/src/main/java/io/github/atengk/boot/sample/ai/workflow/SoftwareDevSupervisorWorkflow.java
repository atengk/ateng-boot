package io.github.atengk.boot.sample.ai.workflow;

import io.github.atengk.boot.ai.graph.supervisor.SupervisorDecision;
import io.github.atengk.boot.ai.graph.supervisor.SupervisorState;
import io.github.atengk.boot.ai.graph.supervisor.SupervisorWorkerWorkflow;
import io.github.atengk.boot.ai.graph.supervisor.WorkerHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 软件研发多角色协同主管-专工认知状态图工作流
 * <p>
 * 模拟研发总监（Supervisor）根据全局研发目标，动态将任务分拆并调度指派至：
 * <ul>
 *     <li><b>researcher (架构调研员)</b>：技术选型与系统架构设计；</li>
 *     <li><b>coder (核心研发员)</b>：业务核心代码落地与实现；</li>
 *     <li><b>reviewer (质量审查员)</b>：代码审查、规范合规性与单测覆盖校验。</li>
 * </ul>
 * 各专工执行完毕后由主管统一汇总输出最终软件交付成果。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component
public class SoftwareDevSupervisorWorkflow extends SupervisorWorkerWorkflow<SupervisorState> {

    public static final String WORKFLOW_NAME = "softwareDevSupervisorWorkflow";

    public SoftwareDevSupervisorWorkflow() {
        super(WORKFLOW_NAME, SupervisorState::new);
    }

    @Override
    protected SupervisorDecision decide(SupervisorState state) {
        Map<String, Object> results = state.getWorkerResults();

        // 1. 若尚未进行架构调研，指派给 researcher
        if (!results.containsKey("researcher")) {
            return SupervisorDecision.next("researcher",
                    "针对目标 [" + state.getTaskObjective() + "] 进行高可用架构方案选型与设计",
                    "阶段1: 架构预研与方案选型");
        }

        // 2. 若架构调研已完成但尚未编码，指派给 coder
        if (!results.containsKey("coder")) {
            return SupervisorDecision.next("coder",
                    "根据架构调研方案落地实现核心业务代码并编写单元测试",
                    "阶段2: 业务编码与单测编写");
        }

        // 3. 若编码完成但尚未审查，指派给 reviewer
        if (!results.containsKey("reviewer")) {
            return SupervisorDecision.next("reviewer",
                    "审查编码实现的架构合规性、圈复杂度与并发安全",
                    "阶段3: 质量合规与安全审查");
        }

        // 4. 全部专工执行完毕，主管做最终收敛汇总
        return SupervisorDecision.finish("【软件研发协同完成】已依次完成技术预研、核心编码与合规审查，交付物与单元测试全绿，准入生产！");
    }

    @Override
    protected Map<String, WorkerHandler<SupervisorState>> registerWorkers() {
        Map<String, WorkerHandler<SupervisorState>> workers = new LinkedHashMap<>();

        // 架构调研员
        workers.put("researcher", (state, instructions) -> {
            log.info("[RESEARCHER] 正在进行架构方案预研: {}", instructions);
            return "架构方案已确立：采用 Spring Boot 3 + Redis 延时双删与分布式锁保证最终一致性";
        });

        // 核心研发员
        workers.put("coder", (state, instructions) -> {
            log.info("[CODER] 正在实现核心代码: {}", instructions);
            return Map.of(
                    "serviceClass", "OrderFulfillmentService",
                    "coverageRate", "92%",
                    "gitCommit", "feat(order): implement fulfillment workflow"
            );
        });

        // 质量审查员
        workers.put("reviewer", (state, instructions) -> {
            log.info("[REVIEWER] 正在执行代码审查: {}", instructions);
            return "审查通过：代码结构清晰、无线程安全隐患、无实体穿透泄漏，达到发布标准";
        });

        return workers;
    }
}
