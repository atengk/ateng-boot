package io.github.atengk.boot.ai.guardrail;

/**
 * AI 内容安全护栏处置动作枚举
 *
 * @author Ateng
 * @since 2026-11-04
 */
public enum GuardrailAction {

    /**
     * 放行通过
     */
    ALLOW,

    /**
     * 拦截阻断 (快速失败)
     */
    BLOCK,

    /**
     * 降级替换 (替换为安全提示文本)
     */
    REPLACE
}
