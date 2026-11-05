package io.github.atengk.boot.ai.ratelimit;

import io.github.atengk.boot.ai.properties.AtengAiRateLimitProperties;
import org.springframework.util.StringUtils;

/**
 * AI 限流规则动态推导解析器
 * <p>
 * 限流规则优先级裁决：多租户模型定制覆盖 > 租户通用限流 > 模型通用限流 > 全局默认保底限流
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public final class RateLimitRuleResolver {

    private RateLimitRuleResolver() {
    }

    /**
     * 解析后的限流规则元数据
     *
     * @param rps 每秒请求数限制 (<= 0 表示不限制)
     * @param tpm 每分钟 Token 数限制 (<= 0 表示不限制)
     */
    public record ResolvedRule(double rps, long tpm) {
        public boolean hasLimit() {
            return rps > 0 || tpm > 0;
        }
    }

    /**
     * 根据租户标识与目标模型名称推导最终生效的 RPS 与 TPM 限流阈值
     *
     * @param props      限流配置属性
     * @param tenantId   租户标识 (可为 null 或空白)
     * @param modelName  模型名称
     * @return 解析后的 ResolvedRule
     */
    public static ResolvedRule resolve(AtengAiRateLimitProperties props, String tenantId, String modelName) {
        if (props == null || !props.isEnabled()) {
            return new ResolvedRule(0, 0);
        }

        double rps = 0;
        long tpm = 0;

        // 1. 尝试多租户级配置
        if (StringUtils.hasText(tenantId) && props.getTenants().containsKey(tenantId.trim())) {
            AtengAiRateLimitProperties.TenantRateLimit tenantConfig = props.getTenants().get(tenantId.trim());
            if (tenantConfig != null) {
                // 1.1 租户针对特定模型的细粒度覆盖
                if (StringUtils.hasText(modelName) && tenantConfig.getModels().containsKey(modelName.trim())) {
                    AtengAiRateLimitProperties.ModelRateLimit modelOverride = tenantConfig.getModels().get(modelName.trim());
                    if (modelOverride != null) {
                        rps = modelOverride.getRps();
                        tpm = modelOverride.getTpm();
                    }
                }
                // 1.2 若未命中特定模型覆盖，取租户通用限流
                if (rps <= 0 && tenantConfig.getRps() > 0) {
                    rps = tenantConfig.getRps();
                }
                if (tpm <= 0 && tenantConfig.getTpm() > 0) {
                    tpm = tenantConfig.getTpm();
                }
            }
        }

        // 2. 尝试模型级全局配置
        if (StringUtils.hasText(modelName) && props.getModels().containsKey(modelName.trim())) {
            AtengAiRateLimitProperties.ModelRateLimit modelConfig = props.getModels().get(modelName.trim());
            if (modelConfig != null) {
                if (rps <= 0 && modelConfig.getRps() > 0) {
                    rps = modelConfig.getRps();
                }
                if (tpm <= 0 && modelConfig.getTpm() > 0) {
                    tpm = modelConfig.getTpm();
                }
            }
        }

        // 3. 兜底全局默认配置
        if (rps <= 0 && props.getDefaultRps() > 0) {
            rps = props.getDefaultRps();
        }
        if (tpm <= 0 && props.getDefaultTpm() > 0) {
            tpm = props.getDefaultTpm();
        }

        return new ResolvedRule(rps, tpm);
    }
}
