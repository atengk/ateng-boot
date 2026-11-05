package io.github.atengk.boot.ai.properties;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AI 大模型百万 Token 财务定价配置
 * <p>
 * 遵循高精度无量纲规范，以每百万 Token 为基准计价单位（元/百万 Token 或 美元/百万 Token），
 * 内部统一基于 {@link BigDecimal} 计算，彻底杜绝浮点数精度截断误差。
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Data
public class ModelPricingConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 每百万输入提示词 Token 价格 (例如 15.00)
     */
    private BigDecimal inputCostPerMillion = BigDecimal.ZERO;

    /**
     * 每百万输出回答 Token 价格 (例如 60.00)
     */
    private BigDecimal outputCostPerMillion = BigDecimal.ZERO;

    public ModelPricingConfig() {
    }

    public ModelPricingConfig(BigDecimal inputCostPerMillion, BigDecimal outputCostPerMillion) {
        this.inputCostPerMillion = inputCostPerMillion != null ? inputCostPerMillion : BigDecimal.ZERO;
        this.outputCostPerMillion = outputCostPerMillion != null ? outputCostPerMillion : BigDecimal.ZERO;
    }

    /**
     * 静态便捷工厂方法：通过 double 数值构建定价配置
     *
     * @param inputCost  每百万输入 Token 成本
     * @param outputCost 每百万输出 Token 成本
     * @return ModelPricingConfig 实例
     */
    public static ModelPricingConfig of(double inputCost, double outputCost) {
        return new ModelPricingConfig(BigDecimal.valueOf(inputCost), BigDecimal.valueOf(outputCost));
    }
}
