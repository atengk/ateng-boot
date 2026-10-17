package io.github.atengk.boot.satoken.openapi;

import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SaTokenOpenApiCustomizer OpenAPI / Knife4j 鉴权组件定制器单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("SaTokenOpenApiCustomizer 鉴权组件定制器单元测试")
class SaTokenOpenApiCustomizerTest {

    @Test
    @DisplayName("测试自动注入 SecurityScheme 与 SecurityRequirement")
    void testCustomiseOpenAPI() {
        SaTokenProperties properties = new SaTokenProperties();
        properties.getOpenapi().setTokenName("custom-token");

        SaTokenOpenApiCustomizer customizer = new SaTokenOpenApiCustomizer(properties);

        OpenAPI openAPI = new OpenAPI();
        customizer.customise(openAPI);

        assertThat(openAPI.getComponents()).isNotNull();
        assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey("custom-token");

        SecurityScheme scheme = openAPI.getComponents().getSecuritySchemes().get("custom-token");
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getName()).isEqualTo("custom-token");
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.HEADER);

        assertThat(openAPI.getSecurity()).isNotEmpty();
        SecurityRequirement requirement = openAPI.getSecurity().get(0);
        assertThat(requirement).containsKey("custom-token");
    }

    @Test
    @DisplayName("测试 null 保护防御")
    void testNullProtection() {
        SaTokenProperties properties = new SaTokenProperties();
        SaTokenOpenApiCustomizer customizer = new SaTokenOpenApiCustomizer(properties);

        // 验证传入 null 时不抛异常
        customizer.customise(null);
    }
}
