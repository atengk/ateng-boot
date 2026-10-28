package io.github.atengk.boot.http.options;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RequestOptionsApplier 单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class RequestOptionsApplierTest {

    @Test
    @DisplayName("测试 resolveUri 正确执行 RFC 3986 编码与中文/特殊字符转义")
    void testResolveUriWithRfc3986Encoding() {
        RequestOptions options = RequestOptions.builder()
                .queryParam("keyword", "你好 世界")
                .queryParam("tag", "java+spring")
                .build();

        URI uri = RequestOptionsApplier.resolveUri("https://api.example.com/search", options);

        assertThat(uri.toString())
                .contains("keyword=%E4%BD%A0%E5%A5%BD%20%E4%B8%96%E7%95%8C")
                .contains("tag=java+spring");
    }

    @Test
    @DisplayName("测试 resolveUri 正确替换 URI 模板路径变量")
    void testResolveUriWithTemplateVariables() {
        RequestOptions options = RequestOptions.builder()
                .uriVariable("userId", 8888)
                .uriVariable("action", "orders")
                .queryParam("status", "SUCCESS")
                .build();

        URI uri = RequestOptionsApplier.resolveUri("https://api.example.com/users/{userId}/{action}", options);

        assertThat(uri.toString())
                .isEqualTo("https://api.example.com/users/8888/orders?status=SUCCESS");
    }

    @Test
    @DisplayName("测试 resolveUri 空 URL 前置防卫拦截")
    void testResolveUriValidation() {
        assertThatThrownBy(() -> RequestOptionsApplier.resolveUri("", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
