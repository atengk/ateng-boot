package io.github.atengk.boot.ai.guardrail;

/**
 * AI 内容安全护栏处置模式枚举
 *
 * @author Ateng
 * @since 2026-11-04
 */
public enum GuardrailMode {

    /**
     * 快速失败：检测到安全合规风险或越狱注入攻击时立即抛出 AiException(AI_CONTENT_RISK_DETECTED)
     */
    FAIL_FAST,

    /**
     * 兜底替换：检测到安全合规风险时将违规内容平滑替换为预设的安全提示文本
     */
    REPLACE
}
