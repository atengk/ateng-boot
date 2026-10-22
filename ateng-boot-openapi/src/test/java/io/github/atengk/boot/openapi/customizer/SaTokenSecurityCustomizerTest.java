package io.github.atengk.boot.openapi.customizer;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sa-Token 锁头鉴权定制器测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("Sa-Token 锁头鉴权定制器测试")
class SaTokenSecurityCustomizerTest {

    @Test
    @DisplayName("测试在空白 OpenAPI 对象上成功注入 SecuritySchemes 与 SecurityRequirement")
    void testCustomizeEmptyOpenAPI() {
        SaTokenSecurityCustomizer customizer = new SaTokenSecurityCustomizer();
        OpenAPI openAPI = new OpenAPI();

        customizer.customize(openAPI);

        Components components = openAPI.getComponents();
        assertThat(components).isNotNull();
        Map<String, SecurityScheme> schemes = components.getSecuritySchemes();
        assertThat(schemes).isNotNull().isNotEmpty();

        // 默认 tokenName 为 satoken (或 Sa-Token 动态配置)
        String schemeName = customizer.resolveTokenName();
        assertThat(schemes).containsKey(schemeName);

        SecurityScheme scheme = schemes.get(schemeName);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(scheme.getName()).isEqualTo(schemeName);

        List<SecurityRequirement> securityList = openAPI.getSecurity();
        assertThat(securityList).isNotNull().hasSize(1);
        assertThat(securityList.get(0)).containsKey(schemeName);
    }

    @Test
    @DisplayName("测试重复调用具备幂等性，不重复添加")
    void testIdempotency() {
        SaTokenSecurityCustomizer customizer = new SaTokenSecurityCustomizer();
        OpenAPI openAPI = new OpenAPI();

        customizer.customize(openAPI);
        customizer.customize(openAPI);

        String schemeName = customizer.resolveTokenName();
        assertThat(openAPI.getComponents().getSecuritySchemes()).hasSize(1);
        assertThat(openAPI.getSecurity()).hasSize(1);
        assertThat(openAPI.getSecurity().get(0)).containsKey(schemeName);
    }

    @Test
    @DisplayName("测试预先已存在 Components 与其他 SecurityScheme 时的保留与追加")
    void testPreExistingComponents() {
        SaTokenSecurityCustomizer customizer = new SaTokenSecurityCustomizer();
        OpenAPI openAPI = new OpenAPI();

        Components existingComponents = new Components();
        existingComponents.addSecuritySchemes("custom-auth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"));
        openAPI.setComponents(existingComponents);

        SecurityRequirement existingReq = new SecurityRequirement().addList("custom-auth");
        openAPI.addSecurityItem(existingReq);

        customizer.customize(openAPI);

        String schemeName = customizer.resolveTokenName();
        assertThat(openAPI.getComponents().getSecuritySchemes()).hasSize(2);
        assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey("custom-auth");
        assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey(schemeName);

        assertThat(openAPI.getSecurity()).hasSize(2);
    }

    @Test
    @DisplayName("测试空安全防御保护 (Null Safety)")
    void testNullSafety() {
        SaTokenSecurityCustomizer customizer = new SaTokenSecurityCustomizer();
        // 传入 null 不抛出异常
        customizer.customize(null);
    }
}
