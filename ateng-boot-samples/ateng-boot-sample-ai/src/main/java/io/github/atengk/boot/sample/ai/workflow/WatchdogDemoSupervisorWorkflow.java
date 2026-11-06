package io.github.atengk.boot.sample.ai.workflow;

import io.github.atengk.boot.ai.graph.supervisor.SupervisorDecision;
import io.github.atengk.boot.ai.graph.supervisor.SupervisorState;
import io.github.atengk.boot.ai.graph.supervisor.SupervisorWorkerWorkflow;
import io.github.atengk.boot.ai.graph.supervisor.WorkerHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 死循环硬看门狗熔断演示工作流
 * <p>
 * 模拟失控智能体在多个专工间无限往复空转场景。
 * 工作流显式设置 {@link #maxTurns()} = 3，验证硬看门狗在达到轮次上限时
 * 主动记录 WARN 告警并安全熔断至终态，防止 Token 消耗与线程死循环。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component
public class WatchdogDemoSupervisorWorkflow extends SupervisorWorkerWorkflow<SupervisorState> {

    public static final String WORKFLOW_NAME = "watchdogDemoSupervisorWorkflow";

    public WatchdogDemoSupervisorWorkflow() {
        super(WORKFLOW_NAME, SupervisorState::new);
    }

    @Override
    protected int maxTurns() {
        // 设置硬看门狗上限为 3 轮用于快速演示熔断
        return 3;
    }

    @Override
    protected SupervisorDecision decide(SupervisorState state) {
        log.info("[WATCHDOG-DEMO] 主管持续决策，当前轮次: {}", state.getTurnCount());
        // 故意不结束，无限派发给 loopWorker 触发看门狗
        return SupervisorDecision.next("loopWorker",
                "请继续处理未决循环子任务（当前轮次: " + state.getTurnCount() + "）",
                "循环规划中...");
    }

    @Override
    protected Map<String, WorkerHandler<SupervisorState>> registerWorkers() {
        return Map.of("loopWorker", (state, instructions) -> {
            log.info("[LOOP-WORKER] 专工执行空转: {}", instructions);
            return "ping-pong-step-" + state.getTurnCount();
        });
    }
}
