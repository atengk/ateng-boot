package io.github.atengk.boot.ai.observability;

import io.github.atengk.boot.ai.properties.AiAuditProperties;
import io.github.atengk.boot.ai.properties.ModelPricingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenCostCalculator 财务成本核算精确计算单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class TokenCostCalculatorTest {

    @Test
    @DisplayName("精确成本计算：按百万 Token 定价计算输入与输出总财务成本 (高精度无截断)")
    void testExactCostCalculation() {
        AiAuditProperties auditProps = new AiAuditProperties();
        // gpt-4o: 输入 15.00 元/百万 Token, 输出 60.00 元/百万 Token
        ModelPricingConfig gpt4oPricing = ModelPricingConfig.of(15.00, 60.00);
        auditProps.setPricings(Map.of("gpt-4o", gpt4oPricing));

        TokenCostCalculator calculator = new TokenCostCalculator(auditProps);

        // 假设输入 1000 tokens, 输出 500 tokens
        // 输入成本 = 1000 * 15 / 1000000 = 0.015000
        // 输出成本 = 500 * 60 / 1000000 = 0.030000
        // 总成本 = 0.045000
        BigDecimal cost = calculator.calculateCost("gpt-4o", 1000, 500);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.045000"));
    }

    @Test
    @DisplayName("定价回退机制：未显式配置定价的模型自动平滑回退至 defaultPricing")
    void testFallbackToDefaultPricing() {
        AiAuditProperties auditProps = new AiAuditProperties();
        // 默认定价：输入 1.00 元/百万, 输出 2.00 元/百万
        auditProps.setDefaultPricing(ModelPricingConfig.of(1.00, 2.00));

        TokenCostCalculator calculator = new TokenCostCalculator(auditProps);

        // 未配置的未知模型
        // 输入 2000 tokens = 2000 * 1 / 1000000 = 0.002000
        // 输出 1000 tokens = 1000 * 2 / 1000000 = 0.002000
        // 总成本 = 0.004000
        BigDecimal cost = calculator.calculateCost("unknown-model", 2000, 1000);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.004000"));
    }

    @Test
    @DisplayName("边界值与空安全防卫：null 或非正数 Token 数量安全计算，绝不崩溃")
    void testDefensiveBoundaryCalculations() {
        AiAuditProperties auditProps = new AiAuditProperties();
        auditProps.setDefaultPricing(ModelPricingConfig.of(2.50, 5.00));
        TokenCostCalculator calculator = new TokenCostCalculator(auditProps);

        // null Token
        assertThat(calculator.calculateCost("any", null, null)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(calculator.calculateCost("any", 100, null)).isEqualByComparingTo(new BigDecimal("0.000250"));
        assertThat(calculator.calculateCost("any", null, 100)).isEqualByComparingTo(new BigDecimal("0.000500"));

        // 0 Token
        assertThat(calculator.calculateCost("any", 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);

        // null 模型名称与 null 审计配置防御
        TokenCostCalculator safeCalculator = new TokenCostCalculator(null);
        assertThat(safeCalculator.calculateCost(null, 100, 100)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
