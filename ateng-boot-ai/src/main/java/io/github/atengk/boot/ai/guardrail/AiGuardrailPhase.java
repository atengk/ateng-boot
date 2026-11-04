package io.github.atengk.boot.ai.guardrail;

/**
 * AI 内容安全护栏审查阶段枚举
 *
 * @author Ateng
 * @since 2026-11-04
 */
public enum AiGuardrailPhase {

    /**
     * 前置提示词输入审查 (防提示词注入、越狱、超长攻击)
     */
    PRE_PROMPT,

    /**
     * 后置大模型生成响应审查 (防涉黄涉暴、违禁敏感词、机密泄露)
     */
    POST_RESPONSE
}
