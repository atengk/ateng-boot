package io.github.atengk.boot.ai.ratelimit;

import io.github.atengk.boot.ai.properties.AtengAiRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流规则解析裁决单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class RateLimitRuleResolverTest {

    @Test
    @DisplayName("多租户特定模型覆盖配置优先级最高")
    void testTenantModelSpecificOverridePriority() {
        AtengAiRateLimitProperties props = new AtengAiRateLimitProperties();
        props.setDefaultRps(10);
        props.setDefaultTpm(100000);

        // 全局模型配置
        AtengAiRateLimitProperties.ModelRateLimit globalModel = new AtengAiRateLimitProperties.ModelRateLimit();
        globalModel.setRps(20);
        globalModel.setTpm(200000);
        props.getModels().put("deepseek-r1", globalModel);

        // 租户通用配置
        AtengAiRateLimitProperties.TenantRateLimit tenantConfig = new AtengAiRateLimitProperties.TenantRateLimit();
        tenantConfig.setRps(30);
        tenantConfig.setTpm(300000);

        // 租户针对 deepseek-r1 定制覆盖
        AtengAiRateLimitProperties.ModelRateLimit tenantModelOverride = new AtengAiRateLimitProperties.ModelRateLimit();
        tenantModelOverride.setRps(5);
        tenantModelOverride.setTpm(50000);
        tenantConfig.getModels().put("deepseek-r1", tenantModelOverride);

        props.getTenants().put("tenant-001", tenantConfig);

        // 验证租户针对 deepseek-r1 生效定制覆盖
        RateLimitRuleResolver.ResolvedRule rule1 = RateLimitRuleResolver.resolve(props, "tenant-001", "deepseek-r1");
        assertThat(rule1.rps()).isEqualTo(5.0);
        assertThat(rule1.tpm()).isEqualTo(50000L);

        // 验证租户调用其他模型时回退到租户通用配置
        RateLimitRuleResolver.ResolvedRule rule2 = RateLimitRuleResolver.resolve(props, "tenant-001", "qwen-max");
        assertThat(rule2.rps()).isEqualTo(30.0);
        assertThat(rule2.tpm()).isEqualTo(300000L);

        // 验证其他租户调用 deepseek-r1 回退到全局模型配置
        RateLimitRuleResolver.ResolvedRule rule3 = RateLimitRuleResolver.resolve(props, "tenant-002", "deepseek-r1");
        assertThat(rule3.rps()).isEqualTo(20.0);
        assertThat(rule3.tpm()).isEqualTo(200000L);

        // 验证其他租户调用其他模型回退到全局默认保底
        RateLimitRuleResolver.ResolvedRule rule4 = RateLimitRuleResolver.resolve(props, "tenant-002", "gpt-4o");
        assertThat(rule4.rps()).isEqualTo(10.0);
        assertThat(rule4.tpm()).isEqualTo(100000L);
    }

    @Test
    @DisplayName("限流器关闭时解析为无限制规则")
    void testDisabledRateLimiterReturnsNoLimit() {
        AtengAiRateLimitProperties props = new AtengAiRateLimitProperties();
        props.setEnabled(false);
        props.setDefaultRps(10);

        RateLimitRuleResolver.ResolvedRule rule = RateLimitRuleResolver.resolve(props, "t1", "m1");
        assertThat(rule.hasLimit()).isFalse();
        assertThat(rule.rps()).isEqualTo(0);
        assertThat(rule.tpm()).isEqualTo(0);
    }
}
