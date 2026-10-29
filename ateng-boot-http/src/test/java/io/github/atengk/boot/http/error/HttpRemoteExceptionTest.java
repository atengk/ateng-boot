package io.github.atengk.boot.http.error;

import io.github.atengk.boot.core.code.CommonResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpRemoteException 单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class HttpRemoteExceptionTest {

    @Test
    @DisplayName("测试 HttpRemoteException 默认状态码映射与字段完整性")
    void testStatusCodeMappingAndFields() {
        URI url = URI.create("https://api.example.com/users/123");
        HttpRemoteException ex404 = new HttpRemoteException(404, HttpMethod.GET, url, "User not found");

        assertThat(ex404.getHttpStatusCode()).isEqualTo(404);
        assertThat(ex404.getRequestMethod()).isEqualTo(HttpMethod.GET);
        assertThat(ex404.getRequestUrl()).isEqualTo(url);
        assertThat(ex404.getResponseBody()).isEqualTo("User not found");
        assertThat(ex404.getResultCode()).isEqualTo(HttpResultCode.REMOTE_HTTP_NOT_FOUND);
        assertThat(ex404.getCode()).isEqualTo(HttpResultCode.REMOTE_HTTP_NOT_FOUND.getCode());
        assertThat(ex404.getMessage()).contains("HTTP 404 GET [https://api.example.com/users/123] - User not found");

        // 验证 400, 401, 403, 500, 502 等状态码语义化映射
        assertThat(new HttpRemoteException(400, HttpMethod.POST, url, "").getResultCode())
                .isEqualTo(HttpResultCode.REMOTE_HTTP_CLIENT_ERROR);
        assertThat(new HttpRemoteException(401, HttpMethod.GET, url, "").getResultCode())
                .isEqualTo(HttpResultCode.REMOTE_HTTP_UNAUTHORIZED);
        assertThat(new HttpRemoteException(403, HttpMethod.DELETE, url, "").getResultCode())
                .isEqualTo(HttpResultCode.REMOTE_HTTP_FORBIDDEN);
        assertThat(new HttpRemoteException(500, HttpMethod.GET, url, "").getResultCode())
                .isEqualTo(HttpResultCode.REMOTE_HTTP_SERVER_ERROR);
        assertThat(new HttpRemoteException(502, HttpMethod.GET, url, "").getResultCode())
                .isEqualTo(HttpResultCode.REMOTE_HTTP_GATEWAY_ERROR);
        assertThat(new HttpRemoteException(503, HttpMethod.GET, url, "").getResultCode())
                .isEqualTo(HttpResultCode.REMOTE_HTTP_SERVICE_UNAVAILABLE);
        assertThat(new HttpRemoteException(504, HttpMethod.GET, url, "").getResultCode())
                .isEqualTo(HttpResultCode.REMOTE_HTTP_TIMEOUT);
    }

    @Test
    @DisplayName("测试 HttpRemoteException 根因因果链保留与自定义错误码")
    void testCausePreservationAndCustomResultCode() {
        URI url = URI.create("https://api.example.com/data");
        RuntimeException rootCause = new RuntimeException("connection timeout");

        HttpRemoteException ex = new HttpRemoteException(
                CommonResultCode.INTERNAL_SERVER_ERROR,
                504,
                HttpMethod.GET,
                url,
                "Gateway Timeout",
                rootCause
        );

        assertThat(ex.getHttpStatusCode()).isEqualTo(504);
        assertThat(ex.getCause()).isSameAs(rootCause);
        assertThat(ex.getResponseBody()).isEqualTo("Gateway Timeout");
    }

    @Test
    @DisplayName("测试 getResponseBodyAs 针对 Class 与 ParameterizedTypeReference 强类型反序列化")
    void testGetResponseBodyAs() {
        URI url = URI.create("https://api.example.com/orders");
        String jsonBody = "{\"orderId\":\"ORD-8888\",\"amount\":199.99,\"items\":[\"apple\",\"banana\"]}";

        HttpRemoteException ex = new HttpRemoteException(400, HttpMethod.POST, url, jsonBody);

        // 1. 反序列化为普通 Class
        ErrorOrderDto order = ex.getResponseBodyAs(ErrorOrderDto.class);
        assertThat(order).isNotNull();
        assertThat(order.orderId()).isEqualTo("ORD-8888");
        assertThat(order.amount()).isEqualTo(199.99);
        assertThat(order.items()).containsExactly("apple", "banana");

        // 2. 反序列化为 ParameterizedTypeReference 泛型复杂对象 (Map<String, Object>)
        java.util.Map<String, Object> map = ex.getResponseBodyAs(new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>() {});
        assertThat(map).isNotNull();
        assertThat(map.get("orderId")).isEqualTo("ORD-8888");

        // 3. 空响应体或 null 目标类型返回 null
        HttpRemoteException emptyEx = new HttpRemoteException(500, HttpMethod.GET, url, null);
        assertThat(emptyEx.getResponseBodyAs(ErrorOrderDto.class)).isNull();
        assertThat(ex.getResponseBodyAs((Class<ErrorOrderDto>) null)).isNull();
        assertThat(ex.getResponseBodyAs((org.springframework.core.ParameterizedTypeReference<ErrorOrderDto>) null)).isNull();
    }

    private record ErrorOrderDto(String orderId, double amount, java.util.List<String> items) {}
}
