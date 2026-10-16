package io.github.atengk.boot.web.cors;

import io.github.atengk.boot.web.properties.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Collections;
import java.util.Objects;

/**
 * 动态跨域配置源实现
 * 实现 Spring 原生 CorsConfigurationSource，基于 CorsOriginProvider 实施请求时动态校验与凭据安全反射
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class DynamicCorsConfigurationSource implements CorsConfigurationSource {

    private final CorsOriginProvider originProvider;
    private final WebProperties.CorsProperties properties;

    public DynamicCorsConfigurationSource(CorsOriginProvider originProvider,
                                          WebProperties.CorsProperties properties) {
        this.originProvider = Objects.requireNonNull(originProvider, "CorsOriginProvider must not be null");
        this.properties = Objects.requireNonNull(properties, "CorsProperties must not be null");
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (!StringUtils.hasText(origin)) {
            return null;
        }

        // 委托 SPI 动态校验来源
        if (!originProvider.isAllowed(origin)) {
            return null;
        }

        // 来源合法，动态构建 CorsConfiguration 并安全反射真实 Origin
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Collections.singletonList(origin));

        if (properties.getAllowedMethods() != null && !properties.getAllowedMethods().isEmpty()) {
            config.setAllowedMethods(properties.getAllowedMethods());
        }
        if (properties.getAllowedHeaders() != null && !properties.getAllowedHeaders().isEmpty()) {
            config.setAllowedHeaders(properties.getAllowedHeaders());
        }
        if (properties.getExposedHeaders() != null && !properties.getExposedHeaders().isEmpty()) {
            config.setExposedHeaders(properties.getExposedHeaders());
        }
        if (properties.getAllowCredentials() != null) {
            config.setAllowCredentials(properties.getAllowCredentials());
        }
        if (properties.getMaxAge() != null) {
            config.setMaxAge(properties.getMaxAge());
        }

        return config;
    }
}
