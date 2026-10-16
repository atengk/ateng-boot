package io.github.atengk.boot.web.cors;

import io.github.atengk.boot.web.properties.WebProperties;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 默认动态跨域来源判定提供者
 * 基于 WebProperties.CorsProperties 配置，支持精确域名、*.domain.com 通配符模式及本地环回开发放行
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class DefaultCorsOriginProvider implements CorsOriginProvider {

    private final WebProperties.CorsProperties properties;

    public DefaultCorsOriginProvider(WebProperties.CorsProperties properties) {
        this.properties = Objects.requireNonNull(properties, "CorsProperties must not be null");
    }

    @Override
    public boolean isAllowed(String origin) {
        if (!StringUtils.hasText(origin)) {
            return false;
        }

        // 1. 本地开发快速放行 (localhost 与 127.0.0.1 任意端口与协议)
        if (properties.isAllowLocalhost() && isLocalhost(origin)) {
            return true;
        }

        List<String> allowedOrigins = properties.getAllowedOrigins();
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return false;
        }

        // 2. 遍历配置规则：通配符 *、精确匹配与模式匹配 (如 *.ateng.com, https://*.ateng.com)
        for (String pattern : allowedOrigins) {
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.equalsIgnoreCase(origin)) {
                return true;
            }
            if (PatternMatchUtils.simpleMatch(pattern.toLowerCase(), origin.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    private boolean isLocalhost(String origin) {
        String lower = origin.toLowerCase();
        return lower.startsWith("http://localhost") || lower.startsWith("https://localhost")
                || lower.startsWith("http://127.0.0.1") || lower.startsWith("https://127.0.0.1");
    }
}
