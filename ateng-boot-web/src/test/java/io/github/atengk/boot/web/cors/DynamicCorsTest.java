package io.github.atengk.boot.web.cors;

import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态 CORS 核心组件单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("动态 CORS 核心组件单元测试")
class DynamicCorsTest {

    @Test
    @DisplayName("DefaultCorsOriginProvider: 精确匹配、通配符模式与本地环回放行验证")
    void testDefaultCorsOriginProvider() {
        WebProperties.CorsProperties properties = new WebProperties.CorsProperties();
        properties.setAllowedOrigins(Arrays.asList("https://admin.ateng.com", "https://*.ateng.com"));
        properties.setAllowLocalhost(true);

        DefaultCorsOriginProvider provider = new DefaultCorsOriginProvider(properties);

        // 精确匹配
        assertThat(provider.isAllowed("https://admin.ateng.com")).isTrue();

        // 通配符模式匹配
        assertThat(provider.isAllowed("https://api.ateng.com")).isTrue();
        assertThat(provider.isAllowed("https://shop.ateng.com")).isTrue();

        // 本地开发放行
        assertThat(provider.isAllowed("http://localhost:5173")).isTrue();
        assertThat(provider.isAllowed("http://127.0.0.1:8080")).isTrue();

        // 非法来源拒绝
        assertThat(provider.isAllowed("https://evil.com")).isFalse();
        assertThat(provider.isAllowed("https://ateng.com.evil.com")).isFalse();
        assertThat(provider.isAllowed("")).isFalse();
        assertThat(provider.isAllowed(null)).isFalse();
    }

    @Test
    @DisplayName("DynamicCorsConfigurationSource: 合法来源安全反射 Origin 与凭据配置")
    void testDynamicCorsConfigurationSourceAllowed() {
        WebProperties.CorsProperties properties = new WebProperties.CorsProperties();
        properties.setAllowedOrigins(List.of("https://*.ateng.com"));
        properties.setAllowCredentials(true);
        properties.setExposedHeaders(List.of("X-Trace-Id"));

        DefaultCorsOriginProvider provider = new DefaultCorsOriginProvider(properties);
        DynamicCorsConfigurationSource source = new DynamicCorsConfigurationSource(provider, properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://portal.ateng.com");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        // 验证动态反射出具体 Origin，而非通配符 *
        assertThat(config.getAllowedOrigins()).containsExactly("https://portal.ateng.com");
        assertThat(config.getAllowCredentials()).isTrue();
        assertThat(config.getExposedHeaders()).contains("X-Trace-Id");
    }

    @Test
    @DisplayName("DynamicCorsConfigurationSource: 非法来源或无 Origin 时返回 null")
    void testDynamicCorsConfigurationSourceDenied() {
        WebProperties.CorsProperties properties = new WebProperties.CorsProperties();
        properties.setAllowedOrigins(List.of("https://admin.ateng.com"));

        DefaultCorsOriginProvider provider = new DefaultCorsOriginProvider(properties);
        DynamicCorsConfigurationSource source = new DynamicCorsConfigurationSource(provider, properties);

        // 非法来源
        MockHttpServletRequest requestDenied = new MockHttpServletRequest();
        requestDenied.addHeader("Origin", "https://hacker.com");
        assertThat(source.getCorsConfiguration(requestDenied)).isNull();

        // 无 Origin 请求
        MockHttpServletRequest requestNoOrigin = new MockHttpServletRequest();
        assertThat(source.getCorsConfiguration(requestNoOrigin)).isNull();
    }
}
