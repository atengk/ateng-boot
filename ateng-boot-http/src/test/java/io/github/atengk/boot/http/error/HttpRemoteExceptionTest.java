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
        assertThat(ex404.getResultCode()).isEqualTo(CommonResultCode.NOT_FOUND);
        assertThat(ex404.getCode()).isEqualTo(CommonResultCode.NOT_FOUND.getCode());
        assertThat(ex404.getMessage()).contains("HTTP 404 GET [https://api.example.com/users/123] - User not found");

        // 验证 400, 401, 403, 405, 429, 500, 502 等状态码映射
        assertThat(new HttpRemoteException(400, HttpMethod.POST, url, "").getResultCode())
                .isEqualTo(CommonResultCode.BAD_REQUEST);
        assertThat(new HttpRemoteException(401, HttpMethod.GET, url, "").getResultCode())
                .isEqualTo(CommonResultCode.UNAUTHORIZED);
        assertThat(new HttpRemoteException(403, HttpMethod.DELETE, url, "").getResultCode())
                .isEqualTo(CommonResultCode.FORBIDDEN);
        assertThat(new HttpRemoteException(405, HttpMethod.PUT, url, "").getResultCode())
                .isEqualTo(CommonResultCode.METHOD_NOT_ALLOWED);
        assertThat(new HttpRemoteException(429, HttpMethod.GET, url, "").getResultCode())
                .isEqualTo(CommonResultCode.TOO_MANY_REQUESTS);
        assertThat(new HttpRemoteException(500, HttpMethod.GET, url, "").getResultCode())
                .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR);
        assertThat(new HttpRemoteException(502, HttpMethod.GET, url, "").getResultCode())
                .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR);
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
}
