package io.github.atengk.boot.http.error;

import io.github.atengk.boot.core.code.IResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HTTP 结果码契约与异常默认映射单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class HttpResultCodeTest {

    @Test
    @DisplayName("状态码映射契约：4xx 映射至对应的远程客户端结果码")
    void shouldMap4xxToRemoteClientResultCodes() {
        assertEquals(HttpResultCode.REMOTE_HTTP_CLIENT_ERROR, HttpResultCode.fromHttpStatus(400));
        assertEquals(HttpResultCode.REMOTE_HTTP_UNAUTHORIZED, HttpResultCode.fromHttpStatus(401));
        assertEquals(HttpResultCode.REMOTE_HTTP_FORBIDDEN, HttpResultCode.fromHttpStatus(403));
        assertEquals(HttpResultCode.REMOTE_HTTP_NOT_FOUND, HttpResultCode.fromHttpStatus(404));
        assertEquals(HttpResultCode.REMOTE_HTTP_CLIENT_ERROR, HttpResultCode.fromHttpStatus(429));
    }

    @Test
    @DisplayName("状态码映射契约：5xx 映射至对应的远程服务端结果码")
    void shouldMap5xxToRemoteServerResultCodes() {
        assertEquals(HttpResultCode.REMOTE_HTTP_SERVER_ERROR, HttpResultCode.fromHttpStatus(500));
        assertEquals(HttpResultCode.REMOTE_HTTP_GATEWAY_ERROR, HttpResultCode.fromHttpStatus(502));
        assertEquals(HttpResultCode.REMOTE_HTTP_SERVICE_UNAVAILABLE, HttpResultCode.fromHttpStatus(503));
        assertEquals(HttpResultCode.REMOTE_HTTP_TIMEOUT, HttpResultCode.fromHttpStatus(504));
    }

    @Test
    @DisplayName("HttpRemoteException 构造验证：默认优先绑定 HttpResultCode 专职结果码")
    void shouldBindHttpResultCodeByDefaultInHttpRemoteException() {
        HttpRemoteException ex404 = new HttpRemoteException(404, HttpMethod.GET, URI.create("https://api.example.com/users/99"), "Not Found");
        assertEquals(40004, ex404.getCode());
        assertEquals(HttpResultCode.REMOTE_HTTP_NOT_FOUND, ex404.getResultCode());

        HttpRemoteException ex502 = new HttpRemoteException(502, HttpMethod.POST, URI.create("https://api.example.com/pay"), "Bad Gateway");
        assertEquals(50002, ex502.getCode());
        assertEquals(HttpResultCode.REMOTE_HTTP_GATEWAY_ERROR, ex502.getResultCode());

        HttpRemoteException ex503 = new HttpRemoteException(503, HttpMethod.GET, URI.create("https://api.example.com/health"), "Unavailable");
        assertEquals(50003, ex503.getCode());
        assertEquals(HttpResultCode.REMOTE_HTTP_SERVICE_UNAVAILABLE, ex503.getResultCode());
    }
}
