package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通用 SecuritySchemes 配置化定制器单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("通用 SecuritySchemes 配置化定制器单元测试")
class SecuritySchemesCustomizerTest {

    @Test
    @DisplayName("测试 HTTP Bearer (JWT) 鉴权方案注册与全局锁头挂载")
    void testHttpBearerJwtScheme() {
        OpenApiProperties properties = new OpenApiProperties();
        OpenApiProperties.SecuritySchemeConfig config = new OpenApiProperties.SecuritySchemeConfig(
                "BearerAuth", "http", "bearer", "JWT", null, "JWT Bearer Token");
        properties.getSecuritySchemes().add(config);

        SecuritySchemesCustomizer customizer = new SecuritySchemesCustomizer(properties);
        OpenAPI openApi = new OpenAPI();
        customizer.customize(openApi);

        Components components = openApi.getComponents();
        assertThat(components).isNotNull();
        Map<String, SecurityScheme> schemes = components.getSecuritySchemes();
        assertThat(schemes).containsKey("BearerAuth");

        SecurityScheme scheme = schemes.get("BearerAuth");
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
        assertThat(scheme.getDescription()).isEqualTo("JWT Bearer Token");

        // 验证全局 SecurityRequirement
        assertThat(openApi.getSecurity()).isNotNull().hasSize(1);
        assertThat(openApi.getSecurity().get(0)).containsKey("BearerAuth");
    }

    @Test
    @DisplayName("测试 ApiKey (Header) 鉴权方案注册与全局锁头挂载")
    void testApiKeyHeaderScheme() {
        OpenApiProperties properties = new OpenApiProperties();
        OpenApiProperties.SecuritySchemeConfig config = new OpenApiProperties.SecuritySchemeConfig(
                "X-API-KEY", "apiKey", null, null, "header", "API Key Header");
        properties.getSecuritySchemes().add(config);

        SecuritySchemesCustomizer customizer = new SecuritySchemesCustomizer(properties);
        OpenAPI openApi = new OpenAPI();
        customizer.customize(openApi);

        Components components = openApi.getComponents();
        assertThat(components).isNotNull();
        Map<String, SecurityScheme> schemes = components.getSecuritySchemes();
        assertThat(schemes).containsKey("X-API-KEY");

        SecurityScheme scheme = schemes.get("X-API-KEY");
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getName()).isEqualTo("X-API-KEY");
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(scheme.getDescription()).isEqualTo("API Key Header");

        assertThat(openApi.getSecurity()).isNotNull().hasSize(1);
        assertThat(openApi.getSecurity().get(0)).containsKey("X-API-KEY");
    }

    @Test
    @DisplayName("测试独立指定 key 与 Query 位置的 ApiKey 方案")
    void testCustomKeyWithQueryApiKey() {
        OpenApiProperties properties = new OpenApiProperties();
        OpenApiProperties.SecuritySchemeConfig config = new OpenApiProperties.SecuritySchemeConfig();
        config.setKey("queryApiKeyAuth");
        config.setName("access_token");
        config.setType("apiKey");
        config.setIn("query");
        config.setDescription("URL Query Token");
        properties.getSecuritySchemes().add(config);

        SecuritySchemesCustomizer customizer = new SecuritySchemesCustomizer(properties);
        OpenAPI openApi = new OpenAPI();
        customizer.customize(openApi);

        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get("queryApiKeyAuth");
        assertThat(scheme).isNotNull();
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getName()).isEqualTo("access_token");
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.QUERY);

        assertThat(openApi.getSecurity().get(0)).containsKey("queryApiKeyAuth");
    }

    @Test
    @DisplayName("测试 global=false 时仅注册 Components 方案，不全局挂载 SecurityRequirement")
    void testNonGlobalScheme() {
        OpenApiProperties properties = new OpenApiProperties();
        OpenApiProperties.SecuritySchemeConfig config = new OpenApiProperties.SecuritySchemeConfig(
                "OptionalAuth", "http", "bearer", "JWT", null, "Optional Auth");
        config.setGlobal(false);
        properties.getSecuritySchemes().add(config);

        SecuritySchemesCustomizer customizer = new SecuritySchemesCustomizer(properties);
        OpenAPI openApi = new OpenAPI();
        customizer.customize(openApi);

        assertThat(openApi.getComponents().getSecuritySchemes()).containsKey("OptionalAuth");
        assertThat(openApi.getSecurity()).isEmpty();
    }

    @Test
    @DisplayName("测试与 SaTokenSecurityCustomizer 鉴权方案平滑正交共存与防重幂等")
    void testIdempotencyAndOrthogonalCoexistence() {
        OpenApiProperties properties = new OpenApiProperties();
        properties.getSecuritySchemes().add(new OpenApiProperties.SecuritySchemeConfig(
                "BearerAuth", "http", "bearer", "JWT", null, "JWT Bearer"));

        SecuritySchemesCustomizer customizer = new SecuritySchemesCustomizer(properties);
        SaTokenSecurityCustomizer saTokenCustomizer = new SaTokenSecurityCustomizer();

        OpenAPI openApi = new OpenAPI();
        // 1. 先由 Sa-Token 定制器注册 satoken 方案
        saTokenCustomizer.customize(openApi);
        // 2. 再由 SecuritySchemesCustomizer 注册 BearerAuth 方案
        customizer.customize(openApi);
        // 3. 重复执行验证幂等防重
        customizer.customize(openApi);

        Map<String, SecurityScheme> schemes = openApi.getComponents().getSecuritySchemes();
        assertThat(schemes).hasSize(2).containsKeys("satoken", "BearerAuth");

        assertThat(openApi.getSecurity()).hasSize(2);
        assertThat(openApi.getSecurity()).anyMatch(sr -> sr.containsKey("satoken"));
        assertThat(openApi.getSecurity()).anyMatch(sr -> sr.containsKey("BearerAuth"));
    }

    @Test
    @DisplayName("测试空安全防御：openApi 为空、配置列表为空或项无效时不抛异常")
    void testNullAndEmptySafety() {
        OpenApiProperties properties = new OpenApiProperties();
        SecuritySchemesCustomizer customizer = new SecuritySchemesCustomizer(properties);

        // 1. openApi 为 null
        customizer.customize(null);

        // 2. 配置列表为空
        OpenAPI openApi = new OpenAPI();
        customizer.customize(openApi);
        assertThat(openApi.getComponents()).isNull();

        // 3. 配置列表包含 null 或空 name 项
        properties.getSecuritySchemes().add(null);
        properties.getSecuritySchemes().add(new OpenApiProperties.SecuritySchemeConfig());
        customizer.customize(openApi);
        assertThat(openApi.getComponents()).isNotNull();
        assertThat(openApi.getComponents().getSecuritySchemes()).isNull();
    }
}
