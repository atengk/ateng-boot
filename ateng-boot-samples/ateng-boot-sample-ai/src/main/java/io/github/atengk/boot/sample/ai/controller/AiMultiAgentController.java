package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.graph.GraphResumeTemplate;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.ai.graph.supervisor.SupervisorState;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.MultiAgentTaskRequest;
import io.github.atengk.boot.sample.ai.dto.MultiAgentWatchdogRequest;
import io.github.atengk.boot.sample.ai.vo.MultiAgentTaskVo;
import io.github.atengk.boot.sample.ai.workflow.SoftwareDevSupervisorWorkflow;
import io.github.atengk.boot.sample.ai.workflow.WatchdogDemoSupervisorWorkflow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多智能体分层协同 (Supervisor-Worker) 体验控制器
 * <p>
 * 提供主管-专工分层认知状态图任务启动、死循环硬看门狗熔断测试以及快照检索 HTTP 接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/multi-agent")
@RequiredArgsConstructor
public class AiMultiAgentController {

    private final SoftwareDevSupervisorWorkflow softwareDevWorkflow;
    private final WatchdogDemoSupervisorWorkflow watchdogDemoWorkflow;
    private final GraphResumeTemplate graphResumeTemplate;

    /**
     * 启动软件研发多角色协同工作流
     *
     * @param request 任务启动请求 DTO
     * @return 协同执行完成结果 VO
     */
    @PostMapping("/start")
    public Result<MultiAgentTaskVo> startMultiAgentTask(@Valid @RequestBody MultiAgentTaskRequest request) {
        log.info("[MULTI-AGENT-HTTP] 接收到软件研发协同任务启动请求: taskObjective={}", request.getTaskObjective());

        SupervisorState initState = new SupervisorState();
        initState.setTaskObjective(request.getTaskObjective());
        if (request.getRequirementDetails() != null && !request.getRequirementDetails().isBlank()) {
            initState.setCurrentPlan("需求背景: " + request.getRequirementDetails());
        }

        StateSnapshotVo snapshot = softwareDevWorkflow.start(initState);
        return Result.success(MultiAgentTaskVo.from(snapshot));
    }

    /**
     * 触发多智能体死循环硬看门狗熔断演示
     *
     * @param request 测试请求 DTO (可选)
     * @return 达到 maxTurns 上限后安全熔断的执行快照 VO
     */
    @PostMapping("/watchdog-test")
    public Result<MultiAgentTaskVo> triggerWatchdogTest(
            @RequestBody(required = false) MultiAgentWatchdogRequest request) {
        String objective = (request != null && request.getTaskObjective() != null && !request.getTaskObjective().isBlank())
                ? request.getTaskObjective()
                : "失控多智能体死循环防卫压力测试";
        log.info("[MULTI-AGENT-HTTP] 接收到死循环看门狗测试请求: taskObjective={}", objective);

        SupervisorState initState = new SupervisorState();
        initState.setTaskObjective(objective);

        StateSnapshotVo snapshot = watchdogDemoWorkflow.start(initState);
        return Result.success(MultiAgentTaskVo.from(snapshot));
    }

    /**
     * 查询指定会话线程的多智能体执行快照
     *
     * @param threadId 工作流会话标识
     * @return 当前状态快照 VO
     */
    @GetMapping("/snapshot/{threadId}")
    public Result<MultiAgentTaskVo> getSnapshot(
            @PathVariable("threadId") @NotBlank(message = "会话标识 threadId 不能为空") String threadId) {
        log.info("[MULTI-AGENT-HTTP] 查询多智能体协同执行快照: threadId={}", threadId);
        StateSnapshotVo snapshot = graphResumeTemplate.getSnapshot(threadId)
                .orElseThrow(() -> new AiException(AiResultCode.AI_GRAPH_SNAPSHOT_NOT_FOUND,
                        "未找到工作流会话 [" + threadId + "] 快照"));
        return Result.success(MultiAgentTaskVo.from(snapshot));
    }
}
