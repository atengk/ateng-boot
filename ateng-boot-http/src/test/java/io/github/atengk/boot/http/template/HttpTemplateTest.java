package io.github.atengk.boot.http.template;

import io.github.atengk.boot.http.error.HttpErrorHandler;
import io.github.atengk.boot.http.error.HttpRemoteException;
import io.github.atengk.boot.http.options.RequestOptions;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HttpTemplate 对称动词、表单提交与元数据穿透单元测试
 * 基于 OkHttp MockWebServer 动态随机端口验证 100% 离线自洽
 *
 * @author Ateng
 * @since 2026-10-29
 */
class HttpTemplateTest {

    private MockWebServer server;
    private HttpTemplate httpTemplate;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        baseUrl = server.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultStatusHandler(new HttpErrorHandler())
                .build();
        httpTemplate = new HttpTemplate(restClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    // ==========================================
    // 1. PATCH 动词测试
    // ==========================================

    @Test
    @DisplayName("PATCH 请求: 发送 JSON 实体并反序列化为指定 Class")
    void testPatchClass() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":1001,\"username\":\"patched_user\"}"));

        Map<String, Object> patchPayload = Map.of("username", "patched_user");
        Map<?, ?> response = httpTemplate.patch(baseUrl + "/users/1001", patchPayload, Map.class);

        assertThat(response).isNotNull();
        assertThat(response.get("username")).isEqualTo("patched_user");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("PATCH");
        assertThat(recorded.getPath()).isEqualTo("/users/1001");
        assertThat(recorded.getHeader("Content-Type")).contains("application/json");
        assertThat(recorded.getBody().readUtf8()).contains("patched_user");
    }

    @Test
    @DisplayName("PATCH 请求: 携带 RequestOptions 并反序列化为复杂泛型")
    void testPatchParameterizedTypeWithRequestOptions() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":1,\"tag\":\"v1\"},{\"id\":2,\"tag\":\"v2\"}]"));

        RequestOptions options = RequestOptions.builder()
                .header("X-Custom-Patch", "patch-opt-1")
                .queryParam("dryRun", "false")
                .readTimeout(Duration.ofSeconds(3))
                .build();

        List<Map<String, Object>> response = httpTemplate.patch(
                baseUrl + "/tags/batch",
                Map.of("action", "refresh"),
                options,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response).hasSize(2);
        assertThat(response.get(0).get("tag")).isEqualTo("v1");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("PATCH");
        assertThat(recorded.getPath()).contains("/tags/batch?dryRun=false");
        assertThat(recorded.getHeader("X-Custom-Patch")).isEqualTo("patch-opt-1");
    }

    // ==========================================
    // 2. HEAD 动词测试
    // ==========================================

    @Test
    @DisplayName("HEAD 请求: 仅获取响应头元数据而不下载任何 Body")
    void testHead() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Length", "1048576")
                .setHeader("ETag", "\"tag-head-12345\""));

        HttpHeaders headers = httpTemplate.head(baseUrl + "/files/large-download.zip");

        assertThat(headers).isNotNull();
        assertThat(headers.getContentLength()).isEqualTo(1048576L);
        assertThat(headers.getETag()).isEqualTo("\"tag-head-12345\"");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("HEAD");
        assertThat(recorded.getPath()).isEqualTo("/files/large-download.zip");
    }

    @Test
    @DisplayName("HEAD 请求: 远端返回 404 抛出 HttpRemoteException")
    void testHeadNotFound() {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> httpTemplate.head(baseUrl + "/files/missing.bin"))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException hre = (HttpRemoteException) ex;
                    assertThat(hre.getHttpStatusCode()).isEqualTo(404);
                });
    }

    // ==========================================
    // 3. OPTIONS 动词测试
    // ==========================================

    @Test
    @DisplayName("OPTIONS 请求: 获取完整响应头及 CORS 策略")
    void testOptions() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(204)
                .setHeader("Allow", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                .setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                .setHeader("Access-Control-Max-Age", "86400"));

        HttpHeaders headers = httpTemplate.options(baseUrl + "/api/resource");

        assertThat(headers).isNotNull();
        assertThat(headers.getFirst("Access-Control-Max-Age")).isEqualTo("86400");
        assertThat(headers.getAllow()).contains(HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("OPTIONS");
    }

    @Test
    @DisplayName("OPTIONS 请求: optionsForAllow 快捷提取允许的 HttpMethod 集合")
    void testOptionsForAllow() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Allow", "GET, POST, HEAD"));

        Set<HttpMethod> allowed = httpTemplate.optionsForAllow(baseUrl + "/api/ping");

        assertThat(allowed).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST, HttpMethod.HEAD);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("OPTIONS");
    }

    // ==========================================
    // 4. getEntity 与 exchange 元数据穿透测试
    // ==========================================

    @Test
    @DisplayName("getEntity 请求: 获取响应头 X-Total-Count 与状态码")
    void testGetEntityClass() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("X-Total-Count", "128")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"page\":1,\"size\":20}"));

        ResponseEntity<Map> responseEntity = httpTemplate.getEntity(baseUrl + "/items", Map.class);

        assertThat(responseEntity.getStatusCode().value()).isEqualTo(200);
        assertThat(responseEntity.getHeaders().getFirst("X-Total-Count")).isEqualTo("128");
        assertThat(responseEntity.getBody()).isNotNull();
        assertThat(responseEntity.getBody().get("size")).isEqualTo(20);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("exchange 通用交换方法: 支持指定 HttpMethod 与请求体")
    void testExchangeCustomMethod() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Location", "/orders/999")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"orderId\":999,\"status\":\"CREATED\"}"));

        RequestOptions options = RequestOptions.builder()
                .header("X-Idempotency-Key", "idem-001")
                .build();

        ResponseEntity<Map> responseEntity = httpTemplate.exchange(
                baseUrl + "/orders",
                HttpMethod.POST,
                Map.of("amount", 100),
                options,
                Map.class
        );

        assertThat(responseEntity.getStatusCode().value()).isEqualTo(201);
        assertThat(responseEntity.getHeaders().getFirst("Location")).isEqualTo("/orders/999");
        assertThat(responseEntity.getBody().get("status")).isEqualTo("CREATED");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("X-Idempotency-Key")).isEqualTo("idem-001");
    }

    // ==========================================
    // 5. postForm 标准表单提交测试
    // ==========================================

    @Test
    @DisplayName("postForm: MultiValueMap 表单提交自动绑定 application/x-www-form-urlencoded")
    void testPostFormMultiValueMap() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"token-abc-123\",\"expires_in\":3600}"));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "my_app_id");
        form.add("client_secret", "my_secret");

        Map<?, ?> response = httpTemplate.postForm(baseUrl + "/oauth2/token", form, Map.class);

        assertThat(response).isNotNull();
        assertThat(response.get("access_token")).isEqualTo("token-abc-123");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Content-Type")).contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        String requestBody = URLDecoder.decode(recorded.getBody().readUtf8(), StandardCharsets.UTF_8);
        assertThat(requestBody).contains("grant_type=client_credentials");
        assertThat(requestBody).contains("client_id=my_app_id");
        assertThat(requestBody).contains("client_secret=my_secret");
    }

    @Test
    @DisplayName("postForm: 普通 Map 便捷重载自动转译为 MultiValueMap")
    void testPostFormPlainMap() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"code\":0}"));

        Map<String, Object> form = Map.of(
                "action", "verify_sms",
                "phone", "13800138000",
                "code", "123456"
        );

        Map<?, ?> response = httpTemplate.postForm(baseUrl + "/sms/verify", form, Map.class);

        assertThat(response).isNotNull();
        assertThat(response.get("success")).isEqualTo(true);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Content-Type")).contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        String requestBody = URLDecoder.decode(recorded.getBody().readUtf8(), StandardCharsets.UTF_8);
        assertThat(requestBody).contains("action=verify_sms");
        assertThat(requestBody).contains("phone=13800138000");
        assertThat(requestBody).contains("code=123456");
    }

    @Test
    @DisplayName("前置防御断言: url 与 responseType 判空防卫")
    void testPreconditionAssertions() {
        assertThatThrownBy(() -> httpTemplate.patch("", Map.of(), Map.class))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> httpTemplate.patch(baseUrl + "/test", Map.of(), (Class<?>) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> httpTemplate.head(""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> httpTemplate.options(""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> httpTemplate.getEntity("", Map.class))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> httpTemplate.postForm("", Map.of(), Map.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
