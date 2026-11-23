package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.graph.GraphResumeTemplate;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.OrderPipelineCancelRequest;
import io.github.atengk.boot.sample.ai.dto.OrderPipelineResumeRequest;
import io.github.atengk.boot.sample.ai.dto.OrderPipelineStartRequest;
import io.github.atengk.boot.sample.ai.model.OrderFulfillmentState;
import io.github.atengk.boot.sample.ai.workflow.OrderFulfillmentPipelineWorkflow;
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
 * 确定性顺序流水线认知工作流 (SequentialPipelineWorkflow) 体验控制器
 * <p>
 * 提供电商订单全流程履约流水线的启动、状态快照检索、高危订单人机协同（HITL）审批断点恢复与主动撤销 RESTful 接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/workflow/pipeline")
@RequiredArgsConstructor
public class AiSequentialWorkflowController {

    private final OrderFulfillmentPipelineWorkflow orderFulfillmentWorkflow;
    private final GraphResumeTemplate graphResumeTemplate;

    /**
     * 启动订单履约流水线工作流
     *
     * @param request 启动请求 DTO
     * @return 初始执行快照 (若触发人工审核则处于 SUSPENDED 挂起态，若普通订单则直接 COMPLETED)
     */
    @PostMapping("/start")
    public Result<StateSnapshotVo> startWorkflow(@Valid @RequestBody OrderPipelineStartRequest request) {
        log.info("[PIPELINE-HTTP] 接收到订单履约流水线启动请求: orderId={}, amount={}, itemType={}, creditScore={}, vipBypass={}",
                request.getOrderId(), request.getAmount(), request.getItemType(), request.getCreditScore(), request.getVipBypass());

        OrderFulfillmentState initState = new OrderFulfillmentState();
        initState.setOrderId(request.getOrderId());
        initState.setAmount(request.getAmount());
        initState.setItemType(request.getItemType());
        initState.setCreditScore(request.getCreditScore());
        initState.setVipBypass(Boolean.TRUE.equals(request.getVipBypass()));
        if (request.getRemarks() != null && !request.getRemarks().isBlank()) {
            initState.setRemarks(request.getRemarks());
        }

        StateSnapshotVo snapshot = orderFulfillmentWorkflow.start(initState);
        return Result.success(snapshot);
    }

    /**
     * 查询指定会话线程的当前快照
     *
     * @param threadId 工作流会话标识
     * @return 当前状态快照视图
     */
    @GetMapping("/snapshot/{threadId}")
    public Result<StateSnapshotVo> getSnapshot(
            @PathVariable("threadId") @NotBlank(message = "会话标识 threadId 不能为空") String threadId) {
        log.info("[PIPELINE-HTTP] 查询流水线执行快照: threadId={}", threadId);
        StateSnapshotVo snapshot = graphResumeTemplate.getSnapshot(threadId)
                .orElseThrow(() -> new AiException(AiResultCode.AI_GRAPH_SNAPSHOT_NOT_FOUND,
                        "未找到流水线会话 [" + threadId + "] 快照"));
        return Result.success(snapshot);
    }

    /**
     * 人工审核断点恢复挂起的流水线 (支持 APPROVED / REJECTED 决断)
     *
     * @param request 恢复请求 DTO
     * @return 恢复后的最新执行快照
     */
    @PostMapping("/resume")
    public Result<StateSnapshotVo> resumeWorkflow(@Valid @RequestBody OrderPipelineResumeRequest request) {
        log.info("[PIPELINE-HTTP] 接收到人工审核恢复请求: threadId={}, action={}, operator={}",
                request.getThreadId(), request.getAction(), request.getOperator());

        Map<String, Object> approvalData = new LinkedHashMap<>();
        approvalData.put("action", request.getAction());
        if (request.getComment() != null) {
            approvalData.put("comment", request.getComment());
        }
        if (request.getOperator() != null) {
            approvalData.put("operator", request.getOperator());
        }

        StateSnapshotVo snapshot = graphResumeTemplate.resume(request.getThreadId(), approvalData);
        return Result.success(snapshot);
    }

    /**
     * 主动撤销挂起或运行中的流水线
     *
     * @param request 撤销请求 DTO
     * @return 撤销后的最终快照 (状态为 CANCELLED)
     */
    @PostMapping("/cancel")
    public Result<StateSnapshotVo> cancelWorkflow(@Valid @RequestBody OrderPipelineCancelRequest request) {
        log.info("[PIPELINE-HTTP] 接收到流水线主动撤销请求: threadId={}, reason={}",
                request.getThreadId(), request.getReason());

        StateSnapshotVo snapshot = graphResumeTemplate.cancel(request.getThreadId(), request.getReason());
        return Result.success(snapshot);
    }
}
