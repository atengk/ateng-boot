package io.github.atengk.boot.http.options;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RequestOptions 载荷不可变性与建造者单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class RequestOptionsTest {

    @Test
    @DisplayName("测试 RequestOptions 建造者与链式属性赋值")
    void testBuilderAndProperties() {
        RequestOptions options = RequestOptions.builder()
                .header("X-Custom-Header", "Value1", "Value2")
                .headers(Map.of("Authorization", "Bearer token123"))
                .queryParam("keyword", "spring boot", "ateng")
                .queryParams(Map.of("page", 1, "size", 20))
                .uriVariable("userId", 1001)
                .uriVariables(Map.of("tenantId", "T_001"))
                .readTimeout(Duration.ofSeconds(5))
                .build();

        assertThat(options.getHeaders().get("X-Custom-Header")).containsExactly("Value1", "Value2");
        assertThat(options.getHeaders().getFirst("Authorization")).isEqualTo("Bearer token123");
        assertThat(options.getQueryParams().get("keyword")).containsExactly("spring boot", "ateng");
        assertThat(options.getQueryParams().getFirst("page")).isEqualTo("1");
        assertThat(options.getQueryParams().getFirst("size")).isEqualTo("20");
        assertThat(options.getUriVariables()).containsEntry("userId", 1001);
        assertThat(options.getUriVariables()).containsEntry("tenantId", "T_001");
        assertThat(options.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("测试 RequestOptions.of 函数式构造语法")
    void testFunctionalFactoryOf() {
        RequestOptions options = RequestOptions.of(builder -> builder
                .header("X-Trace-Id", "trace-abc-123")
                .queryParam("status", "ACTIVE")
                .readTimeout(Duration.ofMillis(800))
        );

        assertThat(options.getHeaders().getFirst("X-Trace-Id")).isEqualTo("trace-abc-123");
        assertThat(options.getQueryParams().getFirst("status")).isEqualTo("ACTIVE");
        assertThat(options.getReadTimeout()).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    @DisplayName("测试 RequestOptions 各属性严格不可变语义")
    void testStrictImmutability() {
        RequestOptions options = RequestOptions.builder()
                .header("X-Test", "1")
                .queryParam("query", "test")
                .uriVariable("key", "val")
                .build();

        // 尝试向 headers 修改，预期抛出 UnsupportedOperationException
        HttpHeaders headers = options.getHeaders();
        assertThatThrownBy(() -> headers.add("X-New", "2"))
                .isInstanceOf(UnsupportedOperationException.class);

        // 尝试向 queryParams 修改，预期抛出 UnsupportedOperationException
        MultiValueMap<String, String> queryParams = options.getQueryParams();
        assertThatThrownBy(() -> queryParams.add("newKey", "val"))
                .isInstanceOf(UnsupportedOperationException.class);

        // 尝试向 uriVariables 修改，预期抛出 UnsupportedOperationException
        Map<String, Object> uriVariables = options.getUriVariables();
        assertThatThrownBy(() -> uriVariables.put("newKey", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("测试 readTimeout 负数防御拦截")
    void testNegativeTimeoutValidation() {
        assertThatThrownBy(() -> RequestOptions.builder().readTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readTimeout 不能为负数");
    }
}
