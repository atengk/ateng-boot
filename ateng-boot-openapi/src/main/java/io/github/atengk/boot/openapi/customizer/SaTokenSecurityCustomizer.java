package io.github.atengk.boot.openapi.customizer;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Sa-Token 锁头鉴权集成定制器
 * <p>
 * 弱依赖探测 Sa-Token 环境，自动向全局 {@link OpenAPI} 注入：
 * <ul>
 *     <li>{@code Components.securitySchemes}：注册名为 {@code satoken}（或 Sa-Token 动态配置的 token-name）的 APIKey 认证方案；</li>
 *     <li>{@code OpenAPI.security}：挂载全局安全要求，使 Swagger UI 右上角自动出现“Authorize / 锁头”弹窗，单次输入全局生效。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class SaTokenSecurityCustomizer implements OpenApiCustomizer {

    public static final String DEFAULT_TOKEN_NAME = "satoken";
    private static final String SA_MANAGER_CLASS = "cn.dev33.satoken.SaManager";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null) {
            return;
        }

        String tokenName = resolveTokenName();

        // 1. 初始化并获取 Components
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }

        // 2. 注册 SecurityScheme（防重幂等）
        if (components.getSecuritySchemes() == null || !components.getSecuritySchemes().containsKey(tokenName)) {
            SecurityScheme securityScheme = new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name(tokenName)
                    .description("Sa-Token 鉴权令牌 (请求头: " + tokenName + "，格式: 直接输入登录返回的 token 字符串)");

            components.addSecuritySchemes(tokenName, securityScheme);
        }

        // 3. 全局挂载 SecurityRequirement（防重幂等）
        List<SecurityRequirement> securityList = openApi.getSecurity();
        if (securityList == null) {
            securityList = new ArrayList<>();
            openApi.setSecurity(securityList);
        }

        boolean alreadyPresent = securityList.stream().anyMatch(sr -> sr.containsKey(tokenName));
        if (!alreadyPresent) {
            openApi.addSecurityItem(new SecurityRequirement().addList(tokenName));
        }
    }

    /**
     * 兼容美式拼写别名方法
     *
     * @param openApi 全局 OpenAPI 对象
     */
    public void customize(OpenAPI openApi) {
        customise(openApi);
    }

    /**
     * 动态解析 Sa-Token 鉴权 Token 名称
     *
     * @return 配置的 Token 名称，类不存在或未配置时回退为 {@value #DEFAULT_TOKEN_NAME}
     */
    public String resolveTokenName() {
        if (ClassUtils.isPresent(SA_MANAGER_CLASS, getClass().getClassLoader())) {
            try {
                cn.dev33.satoken.config.SaTokenConfig config = cn.dev33.satoken.SaManager.getConfig();
                if (config != null && StringUtils.hasText(config.getTokenName())) {
                    return config.getTokenName();
                }
            } catch (Throwable ignored) {
                // 环境未完全初始化或无配置时安全降级
            }
        }
        return DEFAULT_TOKEN_NAME;
    }
}
