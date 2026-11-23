package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.graph.GraphResumeTemplate;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.OrderParallelStartRequest;
import io.github.atengk.boot.sample.ai.model.OrderParallelVerificationState;
import io.github.atengk.boot.sample.ai.workflow.OrderParallelVerificationWorkflow;
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
 * 并行扇出-汇聚认知状态图 (ParallelFanOutWorkflow) 体验控制器
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/workflow/parallel")
@RequiredArgsConstructor
public class AiParallelWorkflowController {

    private final OrderParallelVerificationWorkflow orderParallelVerificationWorkflow;
    private final GraphResumeTemplate graphResumeTemplate;

    /**
     * 触发订单多维并行扇出核验与结果汇聚
     *
     * @param request 启动请求 DTO
     * @return 终态认知图快照视图
     */
    @PostMapping("/start")
    public Result<StateSnapshotVo> startVerification(@Valid @RequestBody OrderParallelStartRequest request) {
        log.info("[PARALLEL-HTTP] 启动订单并行多维核验: orderId={}, userId={}, amount={}, riskDelayMs={}",
                request.getOrderId(), request.getUserId(), request.getAmount(), request.getRiskDelayMs());

        OrderParallelVerificationState initState = new OrderParallelVerificationState();
        initState.setOrderId(request.getOrderId());
        initState.setUserId(request.getUserId());
        initState.setAmount(request.getAmount());
        initState.setSkuCode(request.getSkuCode());
        initState.setRiskDelayMs(request.getRiskDelayMs() != null ? request.getRiskDelayMs() : 0L);

        StateSnapshotVo snapshot = orderParallelVerificationWorkflow.start(initState);
        return Result.success(snapshot);
    }

    /**
     * 查询并行认知工作流执行快照
     *
     * @param threadId 执行会话线程标识
     * @return 快照视图
     */
    @GetMapping("/snapshot/{threadId}")
    public Result<StateSnapshotVo> getSnapshot(
            @PathVariable("threadId") @NotBlank(message = "threadId 不能为空") String threadId) {
        StateSnapshotVo snapshot = graphResumeTemplate.getSnapshot(threadId)
                .orElseThrow(() -> new AiException(AiResultCode.AI_GRAPH_SNAPSHOT_NOT_FOUND,
                        "未找到并行工作流会话 [" + threadId + "] 快照"));
        return Result.success(snapshot);
    }
}
