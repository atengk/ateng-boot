package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.graph.GraphResumeTemplate;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.ArchitectureDebateStartRequest;
import io.github.atengk.boot.sample.ai.dto.DebateCancelRequest;
import io.github.atengk.boot.sample.ai.dto.DebateResumeRequest;
import io.github.atengk.boot.sample.ai.model.ArchitectureDebateState;
import io.github.atengk.boot.sample.ai.workflow.ArchitectureReviewDebateWorkflow;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对抗式多智能体辩论与共识裁决 (DebateConsensusWorkflow) 体验控制器
 * <p>
 * 提供架构技术选型红蓝对抗评审工作流的启动、快照探查、人机断点恢复与主动工单撤销 RESTful 接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/workflow/debate")
@RequiredArgsConstructor
public class AiDebateWorkflowController {

    private final ArchitectureReviewDebateWorkflow architectureReviewDebateWorkflow;
    private final GraphResumeTemplate graphResumeTemplate;

    /**
     * 启动架构评审对抗辩论工作流
     *
     * @param request 启动请求 DTO
     * @return 执行快照视图
     */
    @PostMapping("/start")
    public Result<StateSnapshotVo> startDebate(@Valid @RequestBody ArchitectureDebateStartRequest request) {
        log.info("[DEBATE-HTTP] 接收到架构对抗评审启动请求: title={}, targetQps={}, forceDeadlock={}",
                request.getProposalTitle(), request.getTargetQps(), request.getForceDeadlock());

        ArchitectureDebateState initState = new ArchitectureDebateState();
        initState.setTopic("架构技术选型评审: " + request.getProposalTitle());
        initState.setProposalTitle(request.getProposalTitle());
        initState.setProposalDesc(request.getProposalDesc());
        initState.setTargetQps(request.getTargetQps());
        initState.setForceDeadlock(Boolean.TRUE.equals(request.getForceDeadlock()));

        StateSnapshotVo snapshot = architectureReviewDebateWorkflow.start(initState);
        return Result.success(snapshot);
    }

    /**
     * 查询指定辩论会话线程的当前快照
     *
     * @param threadId 辩论会话标识
     * @return 当前状态快照视图
     */
    @GetMapping("/snapshot/{threadId}")
    public Result<StateSnapshotVo> getSnapshot(
            @PathVariable("threadId") @NotBlank(message = "会话标识 threadId 不能为空") String threadId) {
        log.info("[DEBATE-HTTP] 查询对抗评审执行快照: threadId={}", threadId);
        StateSnapshotVo snapshot = graphResumeTemplate.getSnapshot(threadId)
                .orElseThrow(() -> new AiException(AiResultCode.AI_GRAPH_SNAPSHOT_NOT_FOUND,
                        "未找到辩论会话 [" + threadId + "] 快照"));
        return Result.success(snapshot);
    }

    /**
     * 人工裁决断点恢复挂起的辩论流程
     *
     * @param request 恢复请求 DTO
     * @return 恢复后的最新执行快照
     */
    @PostMapping("/resume")
    public Result<StateSnapshotVo> resumeDebate(@Valid @RequestBody DebateResumeRequest request) {
        log.info("[DEBATE-HTTP] 接收到辩论人工裁决恢复请求: threadId={}, action={}",
                request.getThreadId(), request.getAction());

        Map<String, Object> approvalData = new LinkedHashMap<>();
        approvalData.put("action", request.getAction());
        if (request.getArbiterComment() != null) {
            approvalData.put("comment", request.getArbiterComment());
        }

        StateSnapshotVo snapshot = graphResumeTemplate.resume(request.getThreadId(), approvalData);
        return Result.success(snapshot);
    }

    /**
     * 主动撤销挂起或运行中的辩论流程
     *
     * @param request 撤销请求 DTO
     * @return 撤销后的最终快照 (状态为 CANCELLED)
     */
    @PostMapping("/cancel")
    public Result<StateSnapshotVo> cancelDebate(@Valid @RequestBody DebateCancelRequest request) {
        log.info("[DEBATE-HTTP] 接收到辩论流程主动撤销请求: threadId={}, reason={}",
                request.getThreadId(), request.getReason());

        StateSnapshotVo snapshot = graphResumeTemplate.cancel(request.getThreadId(), request.getReason());
        return Result.success(snapshot);
    }
}
