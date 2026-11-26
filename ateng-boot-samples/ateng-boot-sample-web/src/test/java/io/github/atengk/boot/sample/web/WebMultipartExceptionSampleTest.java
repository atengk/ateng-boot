package io.github.atengk.boot.sample.web;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实嵌入式 Tomcat 下畸形 Multipart 请求全链路拦截集成测试
 * 补全 MockHttpServletRequest 无法解析原始 boundary 与截断流的真实 Servlet 容器验证闭环
 *
 * @author Ateng
 * @since 2026-11-27
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("真实 Tomcat 容器 Multipart 畸形请求端到端集成测试")
class WebMultipartExceptionSampleTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @LocalServerPort
    private int port;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. 缺失 boundary 的 multipart/form-data 请求在真实 Tomcat 中转译为 HTTP 200 业务码 400")
    void shouldReturnBadRequestWhenMultipartBoundaryMissingInRealTomcat() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/sample/web/contract/error/upload"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data")
                .POST(HttpRequest.BodyPublishers.ofString("malformed-multipart-payload", StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("X-Trace-Id").isPresent());

        Result<?> result = JsonUtil.parseObject(response.body(), Result.class);
        assertNotNull(result);
        assertEquals(400, result.getCode());
        assertFalse(result.isSuccess());
        assertEquals("文件上传请求格式不合法或内容解析失败", result.getMsg());
        assertNotNull(result.getTraceId());
        assertFalse(result.getTraceId().isBlank());
    }

    @Test
    @DisplayName("2. 截断畸形 multipart 流在真实 Tomcat 中转译为 HTTP 200 业务码 400（不误判为 500 或断连）")
    void shouldReturnBadRequestWhenMultipartStreamTruncatedInRealTomcat() throws Exception {
        String boundary = "atengTestBoundary2026";
        String truncatedBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"truncated.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "partial-content-without-closing-boundary";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/sample/web/contract/error/upload"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(truncatedBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("X-Trace-Id").isPresent());

        Result<?> result = JsonUtil.parseObject(response.body(), Result.class);
        assertNotNull(result);
        assertEquals(400, result.getCode());
        assertFalse(result.isSuccess());
        assertEquals("文件上传请求格式不合法或内容解析失败", result.getMsg());
    }

    @Test
    @DisplayName("3. 合法标准 multipart/form-data 报文在真实 Tomcat 中正常上传返回文件名")
    void shouldUploadValidMultipartPayloadInRealTomcat() throws Exception {
        String boundary = "atengValidBoundary2026";
        String validBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"sample.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "hello-ateng-boot\r\n"
                + "--" + boundary + "--\r\n";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/sample/web/contract/error/upload"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(validBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());

        Result<?> result = JsonUtil.parseObject(response.body(), Result.class);
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertTrue(result.isSuccess());
        assertEquals("sample.txt", result.getData());
    }
}
