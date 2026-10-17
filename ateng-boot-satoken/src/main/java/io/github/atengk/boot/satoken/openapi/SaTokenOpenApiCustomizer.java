package io.github.atengk.boot.satoken.openapi;

import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

/**
 * Sa-Token OpenAPI / Knife4j 鉴权组件自动挂载定制器
 * 在检测到 Springdoc / Knife4j 时自动在 API 文档右上角注入鉴权锁弹窗与全局 SecurityRequirement
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RequiredArgsConstructor
public class SaTokenOpenApiCustomizer implements GlobalOpenApiCustomizer {

    private final SaTokenProperties properties;

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null) {
            return;
        }
        String tokenName = properties != null && properties.getOpenapi() != null ? properties.getOpenapi().getTokenName() : null;
        if (tokenName == null || tokenName.isBlank()) {
            try {
                tokenName = cn.dev33.satoken.SaManager.getConfig().getTokenName();
            } catch (Exception e) {
                // SaManager 尚未初始化或未加载全局配置时平滑降级，采用默认 tokenName
                log.debug("获取 Sa-Token 运行时全局 tokenName 失败，采用默认值: {}", e.getMessage());
            }
        }
        if (tokenName == null || tokenName.isBlank()) {
            tokenName = "satoken";
        }

        // 1. 注册 SecurityScheme 组件
        SecurityScheme securityScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .name(tokenName)
                .in(SecurityScheme.In.HEADER)
                .description("Sa-Token 访问凭据 (Token)，请在此填入登录后获取的有效 Token");

        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        components.addSecuritySchemes(tokenName, securityScheme);

        // 2. 全局添加 SecurityRequirement
        SecurityRequirement securityRequirement = new SecurityRequirement().addList(tokenName);
        openApi.addSecurityItem(securityRequirement);

        log.info("Sa-Token OpenAPI 鉴权组件已自动挂载: tokenName={}", tokenName);
    }
}
