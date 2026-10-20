package io.github.atengk.boot.sample.http;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.http.error.HttpRemoteException;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("测试 HttpTemplate 对称支持 ParameterizedTypeReference 深度复杂泛型反序列化")
    void testParameterizedTypeReferenceGenericCrud() {
        // 1. 测试 GET 深度嵌套 Result<List<Map<String, Object>>>
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"操作成功\",\"data\":[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]}"));

        Result<List<Map<String, Object>>> getResult = httpTemplate.get(
                baseUrl + "items",
                new ParameterizedTypeReference<>() {}
        );
        assertThat(getResult).isNotNull();
        assertThat(getResult.getCode()).isEqualTo(200);
        assertThat(getResult.getData()).hasSize(2);
        assertThat(getResult.getData().get(0).get("name")).isEqualTo("Alice");

        // 2. 测试 POST 泛型反序列化
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"操作成功\",\"data\":{\"orderId\":999888}}"));

        Result<Map<String, Object>> postResult = httpTemplate.post(
                baseUrl + "orders",
                Map.of("amount", 100),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(postResult).isNotNull();
        assertThat(postResult.getData().get("orderId")).isEqualTo(999888);

        // 3. 测试 PUT 泛型反序列化
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"操作成功\",\"data\":{\"status\":\"PAID\"}}"));

        Result<Map<String, Object>> putResult = httpTemplate.put(
                baseUrl + "orders/999888",
                Map.of("action", "PAY"),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(putResult).isNotNull();
        assertThat(putResult.getData().get("status")).isEqualTo("PAID");

        // 4. 测试 DELETE 泛型反序列化
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"操作成功\",\"data\":true}"));

        Result<Boolean> deleteResult = httpTemplate.delete(
                baseUrl + "orders/999888",
                new ParameterizedTypeReference<>() {}
        );
        assertThat(deleteResult).isNotNull();
        assertThat(deleteResult.getData()).isTrue();
    }

    @Test
    @DisplayName("测试 getOptional 在 404 时安全返回 Optional.empty()，在 200 时返回包含实体")
    void testGetOptionalBehavior() {
        // 1. 模拟 404 返回，Class 模式
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Not Found\"}"));

        Optional<Map> emptyOptional = httpTemplate.getOptional(baseUrl + "users/not-found", Map.class);
        assertThat(emptyOptional).isEmpty();

        // 2. 模拟 404 返回，ParameterizedTypeReference 泛型模式
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Not Found\"}"));

        Optional<Result<Map<String, Object>>> emptyGenericOptional = httpTemplate.getOptional(
                baseUrl + "users/not-found",
                new ParameterizedTypeReference<>() {}
        );
        assertThat(emptyGenericOptional).isEmpty();

        // 3. 模拟 200 返回，Class 模式
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":1002,\"name\":\"Charlie\"}"));

        Optional<Map> presentOptional = httpTemplate.getOptional(baseUrl + "users/1002", Map.class);
        assertThat(presentOptional).isPresent();
        assertThat(presentOptional.get().get("name")).isEqualTo("Charlie");

        // 4. 模拟 200 返回，泛型模式
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"操作成功\",\"data\":{\"userId\":1002}}"));

        Optional<Result<Map<String, Object>>> presentGenericOptional = httpTemplate.getOptional(
                baseUrl + "users/1002",
                new ParameterizedTypeReference<>() {}
        );
        assertThat(presentGenericOptional).isPresent();
        assertThat(presentGenericOptional.get().getData().get("userId")).isEqualTo(1002);
    }

    @Test
    @DisplayName("测试普通 GET 请求遭遇 404 时正确抛出转译后的 HttpRemoteException")
    void testNormalGetThrowsHttpRemoteExceptionOn404() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"resource not found\"}"));

        assertThatThrownBy(() -> httpTemplate.get(baseUrl + "users/non-existent", Map.class))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(404);
                    assertThat(remoteEx.getRequestMethod()).isEqualTo(HttpMethod.GET);
                    assertThat(remoteEx.getResultCode()).isEqualTo(CommonResultCode.NOT_FOUND);
                    assertThat(remoteEx.getResponseBody()).contains("resource not found");
                });
    }

    @Test
    @DisplayName("测试远端服务 500 且返回超长堆栈时响应体安全截断至 2048 字符")
    void testServerErrorTruncationOn500() {
        String longHtmlError = "<html><body><h1>Internal Server Error</h1><pre>" + "STACK_TRACE_LINE\n".repeat(200) + "</pre></body></html>";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "text/html")
                .setBody(longHtmlError));

        assertThatThrownBy(() -> httpTemplate.get(baseUrl + "server-error", Map.class))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(500);
                    assertThat(remoteEx.getResultCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR);
                    assertThat(remoteEx.getResponseBody()).isNotNull();
                    assertThat(remoteEx.getResponseBody()).endsWith("... [truncated]");
                    assertThat(remoteEx.getResponseBody()).hasSize(2048 + "... [truncated]".length());
                });
    }
}
