package io.github.atengk.boot.ai.observability;

import io.github.atengk.boot.ai.properties.AiAuditProperties;
import io.github.atengk.boot.ai.properties.ModelPricingConfig;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * AI 大模型调用财务成本实时核算器
 * <p>
 * 基于多模型百万 Token 定价字典与 Token 实际消耗量，提供高精度财务金额计算：
 * <ul>
 *   <li>高精度计算：统一采用 {@link BigDecimal} 计算，默认保留 6 位小数，杜绝浮点数截断失真</li>
 *   <li>多维定价路由：优先按请求真实调用的模型名称匹配专有单价，未命中时平滑回退至 defaultPricing 兜底</li>
 *   <li>空安全防卫：输入为 null、负数或未配置定价时保底返回 {@link BigDecimal#ZERO}，保障系统韧性</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class TokenCostCalculator {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final int COST_DECIMAL_SCALE = 6;

    private final AiAuditProperties properties;

    public TokenCostCalculator(AiAuditProperties properties) {
        this.properties = properties;
    }

    /**
     * 计算单次大模型调用的财务成本总额 (单位：元或基础货币单位)
     *
     * @param modelName        调用的模型名称或别名
     * @param promptTokens     输入提示词消耗的 Token 数量
     * @param completionTokens 输出回答消耗的 Token 数量
     * @return 预估财务成本总额 (保留 6 位小数，绝不返回 null)
     */
    public BigDecimal calculateCost(String modelName, Integer promptTokens, Integer completionTokens) {
        if (properties == null) {
            return BigDecimal.ZERO.setScale(COST_DECIMAL_SCALE, RoundingMode.HALF_UP);
        }

        ModelPricingConfig pricing = resolvePricing(modelName);
        if (pricing == null) {
            return BigDecimal.ZERO.setScale(COST_DECIMAL_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal inputTokens = (promptTokens != null && promptTokens > 0)
                ? BigDecimal.valueOf(promptTokens)
                : BigDecimal.ZERO;
        BigDecimal outputTokens = (completionTokens != null && completionTokens > 0)
                ? BigDecimal.valueOf(completionTokens)
                : BigDecimal.ZERO;

        BigDecimal inputCostPerMillion = pricing.getInputCostPerMillion() != null
                ? pricing.getInputCostPerMillion()
                : BigDecimal.ZERO;
        BigDecimal outputCostPerMillion = pricing.getOutputCostPerMillion() != null
                ? pricing.getOutputCostPerMillion()
                : BigDecimal.ZERO;

        BigDecimal inputCost = inputTokens.multiply(inputCostPerMillion)
                .divide(ONE_MILLION, COST_DECIMAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputTokens.multiply(outputCostPerMillion)
                .divide(ONE_MILLION, COST_DECIMAL_SCALE, RoundingMode.HALF_UP);

        return inputCost.add(outputCost).setScale(COST_DECIMAL_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 根据模型标识解析对应的定价配置；未匹配时自动平滑回退至默认定价
     *
     * @param modelName 模型名称或别名
     * @return 匹配的 ModelPricingConfig，若无配置保底返回 null
     */
    public ModelPricingConfig resolvePricing(String modelName) {
        if (properties == null) {
            return null;
        }
        Map<String, ModelPricingConfig> pricings = properties.getPricings();
        if (pricings != null && StringUtils.hasText(modelName)) {
            String cleanName = modelName.trim();
            // 精确匹配
            if (pricings.containsKey(cleanName)) {
                return pricings.get(cleanName);
            }
            // 忽略大小写匹配
            for (Map.Entry<String, ModelPricingConfig> entry : pricings.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(cleanName)) {
                    return entry.getValue();
                }
            }
        }
        return properties.getDefaultPricing();
    }
}
