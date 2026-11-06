package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.atengk.boot.ai.annotation.RiskLevel;
import io.github.atengk.boot.ai.tool.SandboxedToolExecutor;
import io.github.atengk.boot.ai.tool.ToolExecutionSandbox;
import io.github.atengk.boot.ai.tool.approval.AgentResumeResult;
import io.github.atengk.boot.ai.tool.approval.ToolApprovalResult;
import io.github.atengk.boot.ai.tool.approval.ToolApprovalTemplate;
import io.github.atengk.boot.ai.tool.approval.ToolApprovalTicket;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.sample.ai.dto.OrderRefundTriggerRequest;
import io.github.atengk.boot.sample.ai.dto.ToolApprovalDecisionRequest;
import io.github.atengk.boot.sample.ai.tool.OrderRefundTool;
import io.github.atengk.boot.sample.ai.vo.OrderRefundTriggerVo;
import io.github.atengk.boot.sample.ai.vo.ToolApprovalResultVo;
import io.github.atengk.boot.sample.ai.vo.ToolApprovalTicketVo;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 声明式高危工具防卫与人机协作审批双模治理体验控制器
 * <p>
 * 提供高危受管工具调用拦截触发、待审批工单检索、人机协同审批通过重放及驳回等全生命周期 REST 接口。
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@RestController
@RequestMapping("/sample/ai/approval")
@RequiredArgsConstructor
public class AiToolApprovalController {

    private final OrderRefundTool orderRefundTool;
    private final ToolApprovalTemplate toolApprovalTemplate;

    private Map<ToolSpecification, ToolExecutor> sandboxedTools;

    @PostConstruct
    public void init() {
        // 注册受管工具 Bean 并包装沙箱执行器
        toolApprovalTemplate.registerTargetBean(orderRefundTool);
        this.sandboxedTools = ToolExecutionSandbox.wrapToolBean(orderRefundTool, toolApprovalTemplate);
    }

    /**
     * 模拟大模型触发订单退款高危操作（触发 @SensitiveTool 自动拦截并签发工单）
     *
     * @param request 退款请求
     * @return 拦截与审批工单视图
     */
    @PostMapping("/trigger-refund")
    public Result<OrderRefundTriggerVo> triggerRefund(@Valid @RequestBody OrderRefundTriggerRequest request) {
        log.info("[AI-APPROVAL-SAMPLE] 接收到大模型退款工具调用请求: orderId={}, amount={}",
                request.getOrderId(), request.getAmount());

        ToolExecutor refundExecutor = null;
        for (Map.Entry<ToolSpecification, ToolExecutor> entry : sandboxedTools.entrySet()) {
            if ("executeRefund".equalsIgnoreCase(entry.getKey().name())) {
                refundExecutor = entry.getValue();
                break;
            }
        }

        if (refundExecutor == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "未找到目标工具方法: executeRefund");
        }

        // 构建工具调用入参 JSON
        Map<String, Object> args = Map.of(
                "orderId", request.getOrderId(),
                "amount", request.getAmount(),
                "reason", request.getReason()
        );

        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .name("executeRefund")
                .arguments(JsonUtil.toJsonString(args))
                .build();

        // 沙箱执行并捕获高危拦截响应
        String response = refundExecutor.execute(toolRequest, null);

        String ticketId = SandboxedToolExecutor.extractTicketId(response).orElse(null);

        OrderRefundTriggerVo vo = OrderRefundTriggerVo.builder()
                .ticketId(ticketId)
                .interceptedMessage(response)
                .approvalRequired(SandboxedToolExecutor.isApprovalRequired(response))
                .riskLevel(RiskLevel.HIGH)
                .approvalType("ORDER_REFUND")
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .build();

        return Result.success(vo);
    }

    /**
     * 查询所有待审批状态的高危工具工单列表
     *
     * @return 待审批工单清单
     */
    @GetMapping("/tickets/pending")
    public Result<List<ToolApprovalTicketVo>> listPendingTickets() {
        List<ToolApprovalTicket> tickets = toolApprovalTemplate.listPending();
        if (tickets == null || tickets.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        List<ToolApprovalTicketVo> voList = tickets.stream().map(this::toTicketVo).toList();
        return Result.success(voList);
    }

    /**
     * 根据工单标识查询工单快照详情
     *
     * @param ticketId 工单标识
     * @return 工单详情视图
     */
    @GetMapping("/tickets/{ticketId}")
    public Result<ToolApprovalTicketVo> getTicket(@PathVariable String ticketId) {
        ToolApprovalTicket ticket = toolApprovalTemplate.getTicket(ticketId)
                .orElseThrow(() -> new BusinessException(CommonResultCode.NOT_FOUND, "未找到指定的工具审批工单: " + ticketId));
        return Result.success(toTicketVo(ticket));
    }

    /**
     * 人工审批核准通过并触发受管重放执行
     *
     * @param ticketId 工单标识
     * @param request  审批意见请求
     * @return 审批重放结果
     */
    @PostMapping("/tickets/{ticketId}/approve")
    public Result<ToolApprovalResultVo> approveTicket(
            @PathVariable String ticketId,
            @RequestBody(required = false) ToolApprovalDecisionRequest request) {
        String comment = (request != null && StringUtils.hasText(request.getComment()))
                ? request.getComment().trim()
                : "管理员审批通过";
        log.info("[AI-APPROVAL-SAMPLE] 管理员核准工单通过: ticketId={}, comment={}", ticketId, comment);
        ToolApprovalResult result = toolApprovalTemplate.approve(ticketId, comment);
        return Result.success(toResultVo(result));
    }

    /**
     * 人工审批核准通过并驱动智能体主动续跑完成最终作答
     *
     * @param ticketId 工单标识
     * @param request  审批意见请求
     * @return 智能体续跑执行结果
     */
    @PostMapping("/tickets/{ticketId}/approve-and-resume")
    public Result<AgentResumeResult> approveAndResumeTicket(
            @PathVariable String ticketId,
            @RequestBody(required = false) ToolApprovalDecisionRequest request) {
        String comment = (request != null && StringUtils.hasText(request.getComment()))
                ? request.getComment().trim()
                : "管理员审批通过并驱动续跑";
        log.info("[AI-APPROVAL-SAMPLE] 管理员核准工单并触发智能体续跑: ticketId={}, comment={}", ticketId, comment);
        AgentResumeResult result = toolApprovalTemplate.approveAndResumeAgent(ticketId, comment);
        return Result.success(result);
    }

    /**
     * 人工审批驳回拒绝高危工具执行
     *
     * @param ticketId 工单标识
     * @param request  驳回原因请求
     * @return 审批驳回结果
     */
    @PostMapping("/tickets/{ticketId}/reject")
    public Result<ToolApprovalResultVo> rejectTicket(
            @PathVariable String ticketId,
            @RequestBody(required = false) ToolApprovalDecisionRequest request) {
        String reason = (request != null && StringUtils.hasText(request.getReason()))
                ? request.getReason().trim()
                : "人工审核拒绝执行";
        log.warn("[AI-APPROVAL-SAMPLE] 管理员拒绝工单执行: ticketId={}, reason={}", ticketId, reason);
        ToolApprovalResult result = toolApprovalTemplate.reject(ticketId, reason);
        return Result.success(toResultVo(result));
    }

    private ToolApprovalTicketVo toTicketVo(ToolApprovalTicket ticket) {
        if (ticket == null) {
            return null;
        }
        return ToolApprovalTicketVo.builder()
                .ticketId(ticket.getTicketId())
                .toolName(ticket.getToolName())
                .targetClassName(ticket.getTargetClassName())
                .methodName(ticket.getMethodName())
                .agentBeanName(ticket.getAgentBeanName())
                .agentInterfaceName(ticket.getAgentInterfaceName())
                .memoryId(ticket.getMemoryId())
                .toolExecutionId(ticket.getToolExecutionId())
                .argumentsJson(ticket.getArgumentsJson())
                .riskLevel(ticket.getRiskLevel())
                .approvalType(ticket.getApprovalType())
                .description(ticket.getDescription())
                .status(ticket.getStatus())
                .traceId(ticket.getTraceId())
                .tenantId(ticket.getTenantId())
                .reviewComment(ticket.getReviewComment())
                .executionResultJson(ticket.getExecutionResultJson())
                .errorMessage(ticket.getErrorMessage())
                .createTime(ticket.getCreateTime())
                .expireTime(ticket.getExpireTime())
                .reviewTime(ticket.getReviewTime())
                .build();
    }

    private ToolApprovalResultVo toResultVo(ToolApprovalResult result) {
        if (result == null) {
            return null;
        }
        return ToolApprovalResultVo.builder()
                .ticketId(result.getTicketId())
                .status(result.getStatus())
                .success(result.isSuccess())
                .result(result.getResult())
                .errorMessage(result.getErrorMessage())
                .executionTimeMs(result.getExecutionTimeMs())
                .build();
    }
}
