package io.github.atengk.boot.ai.guardrail;

import dev.langchain4j.data.message.ChatMessage;
import io.github.atengk.boot.ai.observability.FallbackTokenEstimator;

import java.util.List;

/**
 * AI 双向内容安全护栏核心扩展点 SPI
 * 支持前置输入 (PRE_PROMPT) 防越狱注入与后置输出 (POST_RESPONSE) 敏感词合规审查
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
    GuardrailResult inspectPrompt(String prompt);

    /**
     * 前置多轮对话消息列表输入审查 (PRE_PROMPT)
     * 默认提取对话消息中的纯文本并进行安全审查
     *
     * @param messages 对话消息列表
     * @return 护栏裁定结果 GuardrailResult
     */
    default GuardrailResult inspectMessages(List<ChatMessage> messages) {
        String promptText = FallbackTokenEstimator.extractPromptText(messages);
        return inspectPrompt(promptText);
    }

    /**
     * 后置大模型生成响应文本审查 (POST_RESPONSE)
     *
     * @param responseText 大模型生成回复文本
     * @return 护栏裁定结果 GuardrailResult
     */
    GuardrailResult inspectResponse(String responseText);
}
