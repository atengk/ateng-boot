package io.github.atengk.boot.sample.http;

import io.github.atengk.boot.http.template.HttpTemplate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpTemplate 命令式门面端到端集成验证测试
 * 基于 OkHttp MockWebServer 动态随机端口完成 100% 离线自洽测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootTest
class HttpTemplateSampleTest {

    @Autowired
    private HttpTemplate httpTemplate;

    @Autowired
    private ClientHttpRequestFactory clientHttpRequestFactory;

    private MockWebServer mockWebServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        baseUrl = mockWebServer.url("/").toString();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("验证自动装配与默认 Apache HttpClient 5 生产连接池 Bean 正常注入")
    void testAutoConfigurationAndInjectedBeans() {
        assertThat(httpTemplate).isNotNull();
        assertThat(clientHttpRequestFactory).isNotNull();
        assertThat(clientHttpRequestFactory).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("测试 HttpTemplate 执行 GET 请求并正确反序列化响应数据")
    void testHttpGetSimpleJson() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"success\",\"status\":\"UP\"}"));

        Map<?, ?> result = httpTemplate.get(baseUrl + "health", Map.class);

        assertThat(result).isNotNull();
        assertThat(result.get("message")).isEqualTo("success");
        assertThat(result.get("status")).isEqualTo("UP");

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("GET");
        assertThat(recordedRequest.getPath()).isEqualTo("/health");
    }

    @Test
    @DisplayName("测试 HttpTemplate 执行 POST JSON 请求并正确上送请求体与接收响应")
    void testHttpPostSimpleJson() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":1001,\"name\":\"ateng\",\"result\":\"CREATED\"}"));

        Map<String, Object> requestPayload = Map.of("name", "ateng", "age", 18);
        Map<?, ?> result = httpTemplate.post(baseUrl + "users", requestPayload, Map.class);

        assertThat(result).isNotNull();
        assertThat(result.get("id")).isEqualTo(1001);
        assertThat(result.get("name")).isEqualTo("ateng");

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/users");
        assertThat(recordedRequest.getBody().readUtf8()).contains("\"name\":\"ateng\"");
    }

    @Test
    @DisplayName("测试 HttpTemplate 执行 PUT 与 DELETE 请求")
    void testHttpPutAndDelete() throws InterruptedException {
        // 1. 测试 PUT
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"updated\":true}"));

        Map<?, ?> putResult = httpTemplate.put(baseUrl + "users/1001", Map.of("status", "ACTIVE"), Map.class);
        assertThat(putResult).isNotNull();
        assertThat(putResult.get("updated")).isEqualTo(true);

        RecordedRequest putRequest = mockWebServer.takeRequest();
        assertThat(putRequest.getMethod()).isEqualTo("PUT");
        assertThat(putRequest.getPath()).isEqualTo("/users/1001");

        // 2. 测试 DELETE
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"deleted\":true}"));

        Map<?, ?> deleteResult = httpTemplate.delete(baseUrl + "users/1001", Map.class);
        assertThat(deleteResult).isNotNull();
        assertThat(deleteResult.get("deleted")).isEqualTo(true);

        RecordedRequest deleteRequest = mockWebServer.takeRequest();
        assertThat(deleteRequest.getMethod()).isEqualTo("DELETE");
        assertThat(deleteRequest.getPath()).isEqualTo("/users/1001");
    }
}
