package io.github.atengk.boot.openapi.customizer;

import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 通用 SecuritySchemes 配置化定制器
 * <p>
 * 根据 {@link OpenApiProperties#getSecuritySchemes()} 列表配置，动态向全局 {@link OpenAPI} 注入：
 * <ul>
 *     <li>{@code Components.securitySchemes}：注册通用鉴权方案（如 HTTP Bearer JWT、ApiKey、OAuth2 等）；</li>
 *     <li>{@code OpenAPI.security}：全局挂载对应 {@link SecurityRequirement}，在 Swagger UI 激活 Authorize 锁头弹窗。</li>
 * </ul>
 * 与 {@link SaTokenSecurityCustomizer} 保持正交与幂等兼容。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class SecuritySchemesCustomizer implements OpenApiCustomizer {

    private final OpenApiProperties properties;

    /**
     * 构造通用 SecuritySchemes 定制器
     *
     * @param properties OpenAPI 配置属性
     */
    public SecuritySchemesCustomizer(OpenApiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null) {
            return;
        }

        List<OpenApiProperties.SecuritySchemeConfig> configs = properties.getSecuritySchemes();
        if (CollectionUtils.isEmpty(configs)) {
            return;
        }

        // 1. 初始化并获取 Components
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }

        // 2. 初始化全局 SecurityRequirement 列表
        List<SecurityRequirement> securityList = openApi.getSecurity();
        if (securityList == null) {
            securityList = new ArrayList<>();
            openApi.setSecurity(securityList);
        }

        // 3. 逐项注册 SecurityScheme 与 SecurityRequirement
        for (OpenApiProperties.SecuritySchemeConfig config : configs) {
            if (config == null || !StringUtils.hasText(config.getName())) {
                continue;
            }

            String schemeKey = config.getKey();
            if (!StringUtils.hasText(schemeKey)) {
                schemeKey = config.getName();
            }

            // 注册 SecurityScheme（防重幂等）
            if (components.getSecuritySchemes() == null || !components.getSecuritySchemes().containsKey(schemeKey)) {
                SecurityScheme scheme = buildSecurityScheme(config);
                components.addSecuritySchemes(schemeKey, scheme);
            }

            // 全局挂载 SecurityRequirement（仅当 global = true 且未挂载时追加）
            if (config.isGlobal()) {
                String finalKey = schemeKey;
                boolean alreadyPresent = securityList.stream().anyMatch(sr -> sr.containsKey(finalKey));
                if (!alreadyPresent) {
                    openApi.addSecurityItem(new SecurityRequirement().addList(finalKey));
                }
            }
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
     * 构建 OpenAPI SecurityScheme 方案实体
     *
     * @param config 鉴权配置
     * @return SecurityScheme 对象
     */
    private SecurityScheme buildSecurityScheme(OpenApiProperties.SecuritySchemeConfig config) {
        SecurityScheme.Type type = resolveType(config.getType());
        SecurityScheme scheme = new SecurityScheme().type(type);

        if (type == SecurityScheme.Type.HTTP) {
            scheme.scheme(config.getScheme());
            if (StringUtils.hasText(config.getBearerFormat())) {
                scheme.bearerFormat(config.getBearerFormat());
            }
        } else if (type == SecurityScheme.Type.APIKEY) {
            scheme.name(config.getName());
            scheme.in(resolveIn(config.getIn()));
        }

        if (StringUtils.hasText(config.getDescription())) {
            scheme.description(config.getDescription());
        }

        return scheme;
    }

    /**
     * 解析鉴权类型枚举
     *
     * @param typeStr 类型字符串
     * @return SecurityScheme.Type 枚举
     */
    private SecurityScheme.Type resolveType(String typeStr) {
        if (!StringUtils.hasText(typeStr)) {
            return SecurityScheme.Type.HTTP;
        }
        return switch (typeStr.trim().toLowerCase(Locale.ROOT)) {
            case "apikey" -> SecurityScheme.Type.APIKEY;
            case "oauth2" -> SecurityScheme.Type.OAUTH2;
            case "openidconnect" -> SecurityScheme.Type.OPENIDCONNECT;
            case "mutualtls" -> SecurityScheme.Type.MUTUALTLS;
            default -> SecurityScheme.Type.HTTP;
        };
    }

    /**
     * 解析参数传递位置枚举
     *
     * @param inStr 位置字符串
     * @return SecurityScheme.In 枚举
     */
    private SecurityScheme.In resolveIn(String inStr) {
        if (!StringUtils.hasText(inStr)) {
            return SecurityScheme.In.HEADER;
        }
        return switch (inStr.trim().toLowerCase(Locale.ROOT)) {
            case "query" -> SecurityScheme.In.QUERY;
            case "cookie" -> SecurityScheme.In.COOKIE;
            default -> SecurityScheme.In.HEADER;
        };
    }
}
