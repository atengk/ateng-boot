package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.agent.FinancialAuditAgent;
import io.github.atengk.boot.sample.ai.dto.FinancialAuditRequest;
import io.github.atengk.boot.sample.ai.vo.FinancialAuditResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 声明式大模型智能体控制器
 * <p>
 * 提供受管工具调用、自愈反思与跨轮会话记忆隔离的财务合规智能审计体验接口。
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/agent")
@RequiredArgsConstructor
public class AiAgentController {

    private final FinancialAuditAgent financialAuditAgent;

    /**
     * 单轮无状态财务合规审计决策
     *
     * @param request 财务合规审计入参 DTO
     * @return 统一响应契约包装的财务审计决策结果 VO
     */
    @PostMapping("/audit")
    public Result<FinancialAuditResult> auditOrder(@Valid @RequestBody FinancialAuditRequest request) {
        log.info("[AGENT-HTTP] 接收到单轮财务审计请求: orderId={}", request.getOrderId());
        FinancialAuditResult result = financialAuditAgent.auditOrder(request);
        return Result.success(result);
    }

    /**
     * 跨轮多会话隔离财务合规审计决策
     *
     * @param sessionId 会话隔离唯一标识 (URL 路径参数)
     * @param request   财务合规审计入参 DTO
     * @return 统一响应契约包装的财务审计决策结果 VO
     */
    @PostMapping("/audit/{sessionId}")
    public Result<FinancialAuditResult> auditOrderWithSession(
            @PathVariable("sessionId") @NotBlank(message = "会话标识 sessionId 不能为空") String sessionId,
            @Valid @RequestBody FinancialAuditRequest request) {
        log.info("[AGENT-HTTP] 接收到会话财务审计请求: sessionId={}, orderId={}", sessionId, request.getOrderId());
        FinancialAuditResult result = financialAuditAgent.auditOrderWithSession(sessionId, request);
        return Result.success(result);
    }
}
