package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 内容安全护栏审查裁决结果 VO
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiGuardrailInspectVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否通过安全审查
     */
    private boolean allowed;

    /**
     * 处置动作 (ALLOW / BLOCK / REPLACE)
     */
    private String action;

    /**
     * 审查阶段 (PRE_PROMPT / POST_RESPONSE)
     */
    private String phase;

    /**
     * 命中的违规规则或特征模式 (仅当违规时返回)
     */
    private String matchedRule;

    /**
     * 拦截或降级原因说明
     */
    private String reason;

    /**
     * 安全兜底文本 (若处置动作为 REPLACE 时返回替换后的内容)
     */
    private String safeContent;
}
