package io.github.atengk.boot.ai.guardrail;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * AI 内容安全护栏判定结果
 * 承载规则判定动作、命中特征与安全兜底内容
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否允许放行
     */
    private boolean allowed;

    /**
     * 处置动作：ALLOW 放行，BLOCK 拦截阻断，REPLACE 降级替换
     */
    private GuardrailAction action;

    /**
     * 护栏审查阶段：PRE_PROMPT 前置提示词审查，POST_RESPONSE 后置模型回复审查
     */
    private AiGuardrailPhase phase;

    /**
     * 命中的规则标识或特征（例如 SENSITIVE_KEYWORD:xxx, PROMPT_INJECTION:xxx, MAX_LENGTH_EXCEEDED）
     */
    private String matchedRule;

    /**
     * 拦截或替换原因描述
     */
    private String reason;

    /**
     * 安全有效内容（放行时为原始内容，替换时为兜底安全文本）
     */
    private String safeContent;

    /**
     * 快速构建放行通过结果
     *
     * @param content 放行文本内容
     * @param phase   当前审查阶段
     * @return GuardrailResult 放行实例
     */
    public static GuardrailResult allow(String content, AiGuardrailPhase phase) {
        return GuardrailResult.builder()
                .allowed(true)
                .action(GuardrailAction.ALLOW)
                .phase(phase)
                .safeContent(content)
                .build();
    }

    /**
     * 快速构建阻断拦截结果
     *
     * @param phase       当前审查阶段
     * @param matchedRule 命中的规则标识
     * @param reason      拦截原因描述
     * @return GuardrailResult 阻断实例
     */
    public static GuardrailResult block(AiGuardrailPhase phase, String matchedRule, String reason) {
        return GuardrailResult.builder()
                .allowed(false)
                .action(GuardrailAction.BLOCK)
                .phase(phase)
                .matchedRule(matchedRule)
                .reason(reason)
                .build();
    }

    /**
     * 快速构建降级替换结果
     *
     * @param safeContent 替换后的安全提示文本
     * @param phase       当前审查阶段
     * @param matchedRule 命中的规则标识
     * @param reason      拦截原因描述
     * @return GuardrailResult 替换实例
     */
    public static GuardrailResult replace(String safeContent, AiGuardrailPhase phase, String matchedRule, String reason) {
        return GuardrailResult.builder()
                .allowed(false)
                .action(GuardrailAction.REPLACE)
                .phase(phase)
                .matchedRule(matchedRule)
                .reason(reason)
                .safeContent(safeContent)
                .build();
    }
}
