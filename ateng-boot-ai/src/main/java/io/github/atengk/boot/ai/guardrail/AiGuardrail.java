package io.github.atengk.boot.ai.guardrail;

import dev.langchain4j.data.message.ChatMessage;
import io.github.atengk.boot.ai.observability.FallbackTokenEstimator;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AI 双向内容安全护栏核心扩展点 SPI
 * 支持前置输入 (PRE_PROMPT) 防越狱注入与后置输出 (POST_RESPONSE) 敏感词合规审查，并支持多租户与用户上下文透传
 *
 * @author Ateng
 * @since 2026-11-04
 */
public interface AiGuardrail {

    /**
     * 前置提示词输入审查 (PRE_PROMPT)
     *
     * @param prompt 原始提示词
     * @return 护栏裁定结果 GuardrailResult
     */
    default GuardrailResult inspectPrompt(String prompt) {
        return inspectPrompt(prompt, Collections.emptyMap());
    }

    /**
     * 携带上下文的前置提示词输入审查 (PRE_PROMPT)
     *
     * @param prompt  原始提示词
     * @param context 业务审计与安全上下文 (如 tenantId, userId 等)
     * @return 护栏裁定结果 GuardrailResult
     */
    GuardrailResult inspectPrompt(String prompt, Map<String, Object> context);

    /**
     * 前置多轮对话消息列表输入审查 (PRE_PROMPT)
     * 默认提取对话消息中的纯文本并进行安全审查
     *
     * @param messages 对话消息列表
     * @return 护栏裁定结果 GuardrailResult
     */
    default GuardrailResult inspectMessages(List<ChatMessage> messages) {
        return inspectMessages(messages, Collections.emptyMap());
    }

    /**
     * 携带上下文的前置多轮对话消息列表输入审查 (PRE_PROMPT)
     *
     * @param messages 对话消息列表
     * @param context  业务审计与安全上下文
     * @return 护栏裁定结果 GuardrailResult
     */
    default GuardrailResult inspectMessages(List<ChatMessage> messages, Map<String, Object> context) {
        String promptText = FallbackTokenEstimator.extractPromptText(messages);
        return inspectPrompt(promptText, context);
    }

    /**
     * 后置大模型生成响应文本审查 (POST_RESPONSE)
     *
     * @param responseText 大模型生成回复文本
     * @return 护栏裁定结果 GuardrailResult
     */
    default GuardrailResult inspectResponse(String responseText) {
        return inspectResponse(responseText, Collections.emptyMap());
    }

    /**
     * 携带上下文的后置大模型生成响应文本审查 (POST_RESPONSE)
     *
     * @param responseText 大模型生成回复文本
     * @param context      业务审计与安全上下文
     * @return 护栏裁定结果 GuardrailResult
     */
    GuardrailResult inspectResponse(String responseText, Map<String, Object> context);
}
