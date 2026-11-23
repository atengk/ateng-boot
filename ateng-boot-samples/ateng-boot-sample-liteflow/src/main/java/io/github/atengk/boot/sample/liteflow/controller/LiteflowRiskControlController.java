package io.github.atengk.boot.sample.liteflow.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.trace.LiteflowTraceResult;
import io.github.atengk.boot.sample.liteflow.context.RiskApprovalContext;
import io.github.atengk.boot.sample.liteflow.model.RiskApprovalRequest;
import io.github.atengk.boot.sample.liteflow.model.RiskApprovalResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 金融信贷风控多维审批接口控制器
 * 演示：IF黑名单前置阻断 -> 并行反欺诈与征信评估 -> SWITCH三路决策分流(核准/人工/拒绝)
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@RestController
@RequestMapping("/sample/liteflow/risk")
@RequiredArgsConstructor
public class LiteflowRiskControlController {

    private final LiteflowTemplate liteflowTemplate;

    /**
     * 信贷风控多维授信审批
     *
     * @param request 审批请求
     * @return 审批决策与核准额度
     */
    @PostMapping("/evaluate")
    public Result<RiskApprovalResponse> evaluateRisk(@Valid @RequestBody RiskApprovalRequest request) {
        log.info("【信贷风控】接收授信风控评估申请：applicantId={}, amount={}, creditScore={}",
                request.getApplicantId(), request.getApplyAmount(), request.getCreditScore());

        RiskApprovalContext context = new RiskApprovalContext();
        context.setApplicantId(request.getApplicantId());
        context.setIdCard(request.getIdCard());
        context.setApplyAmount(request.getApplyAmount());
        context.setCreditScore(request.getCreditScore());

        LiteflowTraceResult<RiskApprovalContext> traceResult = liteflowTemplate.executeWithTrace(
                "risk-approval-flow", request, context);

        RiskApprovalContext finalCtx = traceResult.getContext();
        String traceSummary = traceResult.getTrace() != null
                ? traceResult.getTrace().formatSummary()
                : "N/A";

        RiskApprovalResponse response = RiskApprovalResponse.builder()
                .applicantId(finalCtx.getApplicantId())
                .approved(finalCtx.isApproved())
                .approvedAmount(finalCtx.getApprovedAmount())
                .riskLevel(finalCtx.getRiskLevel())
                .decision(finalCtx.getDecision())
                .reason(finalCtx.getDecisionReason())
                .traceSummary(traceSummary)
                .build();

        return Result.success(response);
    }
}
