package io.github.atengk.boot.sample.ai.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * 财务合规审计决策结果对象
 * <p>
 * 挂载 JSR-303 防卫注解，由智能体动态代理底层 {@code JsonPayloadSanitizer} 与校验器统一执行防幻觉格式防卫。
 *
 * @param status    决策状态 (例如 APPROVED, REJECTED, REQUIRES_MANUAL_REVIEW)
 * @param analysis  合规审计分析报告
 * @param riskScore 风险评分 (0-100)
 * @param riskTags  合规与风险标签列表
 *
 * @author Ateng
 * @since 2026-11-03
 */
public record FinancialAuditResult(
        @NotBlank(message = "审核决策状态不能为空")
        String status,

        @NotBlank(message = "合规审计分析报告不能为空")
        String analysis,

        @NotNull(message = "风险评分不能为空")
        @Min(value = 0, message = "风险评分不能小于 0")
        @Max(value = 100, message = "风险评分不能大于 100")
        Integer riskScore,

        List<String> riskTags
) {
    public FinancialAuditResult {
        if (riskTags == null) {
            riskTags = Collections.emptyList();
        }
    }
}
