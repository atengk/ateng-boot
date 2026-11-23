package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.graph.GraphResumeTemplate;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.OrderReviewCancelRequest;
import io.github.atengk.boot.sample.ai.dto.OrderReviewResumeRequest;
import io.github.atengk.boot.sample.ai.dto.OrderReviewStartRequest;
import io.github.atengk.boot.sample.ai.model.OrderReviewState;
import io.github.atengk.boot.sample.ai.workflow.OrderReviewGraphWorkflow;
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
 * 认知状态图工作流体验控制器
 * <p>
 * 提供订单风控审核全生命周期的启动、快照探查、人机协作（HITL）审批恢复与工单撤销 RESTful 接口。
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/workflow/order-review")
@RequiredArgsConstructor
public class AiWorkflowController {

    private final OrderReviewGraphWorkflow orderReviewWorkflow;
    private final GraphResumeTemplate graphResumeTemplate;

    /**
     * 启动订单风控审核工作流
     *
     * @param request 启动请求 DTO
     * @return 初始执行快照 (若触发人工审核则处于 SUSPENDED 挂起态，若小额放行则直接 COMPLETED)
     */
    @PostMapping("/start")
    public Result<StateSnapshotVo> startWorkflow(@Valid @RequestBody OrderReviewStartRequest request) {
        log.info("[WORKFLOW-HTTP] 接收到订单风控审核启动请求: orderId={}, amount={}, score={}",
                request.getOrderId(), request.getAmount(), request.getCreditScore());

        OrderReviewState initState = new OrderReviewState();
        initState.set(OrderReviewState.ORDER_ID, request.getOrderId());
        initState.set(OrderReviewState.AMOUNT, request.getAmount());
        initState.set(OrderReviewState.CREDIT_SCORE, request.getCreditScore());
        if (request.getRemarks() != null && !request.getRemarks().isBlank()) {
            initState.set(OrderReviewState.REMARKS, request.getRemarks());
        }

        StateSnapshotVo snapshot = orderReviewWorkflow.start(initState);
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
        log.info("[WORKFLOW-HTTP] 查询工作流执行快照: threadId={}", threadId);
        StateSnapshotVo snapshot = graphResumeTemplate.getSnapshot(threadId)
                .orElseThrow(() -> new AiException(AiResultCode.AI_GRAPH_SNAPSHOT_NOT_FOUND,
                        "未找到工作流会话 [" + threadId + "] 快照"));
        return Result.success(snapshot);
    }

    /**
     * 人工审批恢复挂起的工作流 (支持 PASSED / REJECTED / REQUEST_DOCS 决断)
     *
     * @param request 恢复请求 DTO
     * @return 恢复后的最新执行快照
     */
    @PostMapping("/resume")
    public Result<StateSnapshotVo> resumeWorkflow(@Valid @RequestBody OrderReviewResumeRequest request) {
        log.info("[WORKFLOW-HTTP] 接收到人工审批恢复请求: threadId={}, action={}",
                request.getThreadId(), request.getAction());

        Map<String, Object> approvalData = new LinkedHashMap<>();
        approvalData.put("action", request.getAction());
        if (request.getComment() != null) {
            approvalData.put("comment", request.getComment());
        }

        StateSnapshotVo snapshot = graphResumeTemplate.resume(
                request.getThreadId(),
                approvalData,
                state -> {
                    if (request.getAdditionalDocs() != null && !request.getAdditionalDocs().isBlank()) {
                        state.set(OrderReviewState.ADDITIONAL_DOCS, request.getAdditionalDocs());
                    }
                }
        );
        return Result.success(snapshot);
    }

    /**
     * 主动撤销挂起的工作流
     *
     * @param request 撤销请求 DTO
     * @return 撤销后的最终快照 (状态为 CANCELLED)
     */
    @PostMapping("/cancel")
    public Result<StateSnapshotVo> cancelWorkflow(@Valid @RequestBody OrderReviewCancelRequest request) {
        log.info("[WORKFLOW-HTTP] 接收到工作流撤销请求: threadId={}, reason={}",
                request.getThreadId(), request.getReason());

        StateSnapshotVo snapshot = graphResumeTemplate.cancel(request.getThreadId(), request.getReason());
        return Result.success(snapshot);
    }
}
