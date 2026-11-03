package io.github.atengk.boot.sample.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.sample.ai.dto.FinancialAuditRequest;
import io.github.atengk.boot.sample.ai.tool.OrderTools;
import io.github.atengk.boot.sample.ai.vo.FinancialAuditResult;

/**
 * 财务合规审计声明式智能体
 * <p>
 * 声明式挂载受管工具 {@link OrderTools}，面向履约订单开展多维度核验与风控审计。
 *
 * @author Ateng
 * @since 2026-11-03
 */
@AiAgent(tools = {OrderTools.class})
@SystemMessage("你是一名资深财务合规审计专家，请调用工具查询订单与仓储履约状态，并给出客观严谨的合规审计决策报告。请以 JSON 格式输出，包含 status、analysis、riskScore、riskTags 字段。")
public interface FinancialAuditAgent {

    /**
     * 单轮无状态财务审计决策
     *
     * @param request 财务合规审计请求
     * @return 财务合规审计决策报告
     */
    FinancialAuditResult auditOrder(@UserMessage FinancialAuditRequest request);

    /**
     * 支持跨轮会话记忆漫游的财务审计交互
     *
     * @param sessionId 会话隔离唯一标识
     * @param request   财务合规审计请求
     * @return 财务合规审计决策报告
     */
    FinancialAuditResult auditOrderWithSession(@MemoryId String sessionId, @UserMessage FinancialAuditRequest request);
}
