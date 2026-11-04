package io.github.atengk.boot.ai.tenant;

import io.github.atengk.boot.ai.properties.AtengAiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SaaS 多租户动态大模型切源解析器单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class AiTenantModelResolverTest {

    @Test
    @DisplayName("租户未配置专有模型时平滑回退至原始请求模型或默认模型")
    void testFallbackToRequestedModel() {
        AtengAiProperties properties = new AtengAiProperties();
        DefaultAiTenantModelResolver resolver = new DefaultAiTenantModelResolver(properties);

        // 1. 无租户标识
        assertThat(resolver.resolveModel(null, "gpt-4o")).isEqualTo("gpt-4o");
        assertThat(resolver.resolveModel("", "gpt-4o")).isEqualTo("gpt-4o");
        assertThat(resolver.resolveModel("   ", "gpt-4o")).isEqualTo("gpt-4o");

        // 2. 有租户标识但未在配置中绑定专属模型
        assertThat(resolver.resolveModel("tenant-unregistered", "deepseek-chat")).isEqualTo("deepseek-chat");

        // 3. 原始请求未指定模型 (null) 时平滑回退为 null (下游由全局默认模型兜底)
        assertThat(resolver.resolveModel("tenant-unregistered", null)).isNull();
    }

    @Test
    @DisplayName("租户在配置中显式绑定专有模型时优先路由至绑定模型")
    void testResolveConfiguredTenantModel() {
        AtengAiProperties properties = new AtengAiProperties();
        properties.setTenantModels(new java.util.LinkedHashMap<>(Map.of(
                "tenant-vip", "deepseek-r1",
                "tenant-finance", "qwen-max"
        )));

        DefaultAiTenantModelResolver resolver = new DefaultAiTenantModelResolver(properties);

        // 命中 VIP 租户专属模型
        assertThat(resolver.resolveModel("tenant-vip", "gpt-4o")).isEqualTo("deepseek-r1");
        assertThat(resolver.resolveModel(" tenant-vip ", null)).isEqualTo("deepseek-r1");

        // 命中金融租户专属模型
        assertThat(resolver.resolveModel("tenant-finance", "deepseek-chat")).isEqualTo("qwen-max");

        // 未配置专属模型则回退请求模型
        assertThat(resolver.resolveModel("tenant-standard", "gpt-4o")).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("支持自定义扩展 AiTenantModelResolver SPI 实现动态数据库/配置中心解析")
    void testCustomTenantModelResolverSpi() {
        Map<String, String> dynamicDb = Map.of("tenant-custom-01", "custom-llm-v2");

        AiTenantModelResolver customResolver = (tenantId, requestedModel) -> {
            if (tenantId != null && dynamicDb.containsKey(tenantId)) {
                return dynamicDb.get(tenantId);
            }
            return requestedModel;
        };

        assertThat(customResolver.resolveModel("tenant-custom-01", "default-model")).isEqualTo("custom-llm-v2");
        assertThat(customResolver.resolveModel("other-tenant", "default-model")).isEqualTo("default-model");
    }
}
