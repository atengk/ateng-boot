package io.github.atengk.boot.http.template;

import io.github.atengk.boot.http.constant.HttpConstants;
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
import org.springframework.core.io.ByteArrayResource;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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

        assertThatThrownBy(() -> httpTemplate.consumeStream("", line -> {}))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> httpTemplate.consumeStream(baseUrl + "/test", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> httpTemplate.consumeStream(baseUrl + "/test", null, null, null, line -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==========================================
    // 7. 大模型与事件流流式消费 (consumeStream)
    // ==========================================

    @Test
    @DisplayName("AI 大模型 SSE 文本流式消费: 按行推流并保留空行事件切片边界 (RFC 8895)")
    void testConsumeStreamSseEvents() throws InterruptedException {
        String ssePayload = """
                data: {"id":1,"token":"Hello"}
                
                data: {"id":2,"token":"World"}
                
                data: [DONE]
                """;

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody(ssePayload));

        List<String> receivedLines = new ArrayList<>();
        httpTemplate.consumeStream(baseUrl + "/v1/chat/completions/stream", receivedLines::add);

        // 验证行流式消费完整保留了数据行与作为 SSE 事件分界符的空行 ("")
        assertThat(receivedLines).containsExactly(
                "data: {\"id\":1,\"token\":\"Hello\"}",
                "",
                "data: {\"id\":2,\"token\":\"World\"}",
                "",
                "data: [DONE]"
        );

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getHeader("Accept")).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(recorded.getHeader(HttpConstants.HEADER_STREAM_TRANSFER)).isEqualTo("true");
    }

    @Test
    @DisplayName("AI 大模型流式消费 POST 场景: 携带 JSON 载荷与 RequestOptions 请求头")
    void testConsumeStreamPostWithBodyAndOptions() throws InterruptedException {
        String ndjsonPayload = "{\"step\":1,\"content\":\"Thinking\"}\n{\"step\":2,\"content\":\"Done\"}\n";

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/x-ndjson; charset=utf-8")
                .setBody(ndjsonPayload));

        RequestOptions options = RequestOptions.builder()
                .header("Authorization", "Bearer sk-test-123456")
                .queryParam("model", "deepseek-r1")
                .build();

        Map<String, Object> requestPayload = Map.of("prompt", "Hello AI", "stream", true);
        List<String> receivedLines = new ArrayList<>();

        httpTemplate.consumeStream(baseUrl + "/api/llm/stream", HttpMethod.POST, requestPayload, options, receivedLines::add);

        assertThat(receivedLines).containsExactly(
                "{\"step\":1,\"content\":\"Thinking\"}",
                "{\"step\":2,\"content\":\"Done\"}"
        );

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/llm/stream?model=deepseek-r1");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test-123456");
        assertThat(recorded.getHeader(HttpConstants.HEADER_STREAM_TRANSFER)).isEqualTo("true");
        assertThat(recorded.getBody().readUtf8()).contains("\"prompt\":\"Hello AI\"");
    }

    @Test
    @DisplayName("流式消费异常转译: 服务端返回 500 时直接抛出 HttpRemoteException 并终止流")
    void testConsumeStreamErrorHandling() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":500,\"message\":\"LLM service unavailable\"}"));

        List<String> lines = new ArrayList<>();
        assertThatThrownBy(() -> httpTemplate.consumeStream(baseUrl + "/stream-err", lines::add))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException hre = (HttpRemoteException) ex;
                    assertThat(hre.getHttpStatusCode()).isEqualTo(500);
                    assertThat(hre.getResponseBody()).contains("LLM service unavailable");
                });

        assertThat(lines).isEmpty();
    }

    @Test
    @DisplayName("流式消费确定性资源释放: 回调中途异常抛出时自动关闭底层网络流")
    void testConsumeStreamEarlyTerminationClosesStream() {
        String multiline = "line1\nline2\nline3\nline4\nline5\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody(multiline));

        List<String> lines = new ArrayList<>();
        assertThatThrownBy(() -> httpTemplate.consumeStream(baseUrl + "/stream-abort", line -> {
            lines.add(line);
            if (lines.size() >= 2) {
                throw new IllegalStateException("客户端主动中止流式消费");
            }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("客户端主动中止流式消费");

        assertThat(lines).containsExactly("line1", "line2");
    }

    // ==========================================
    // 8. 直通大流上传与拦截器旁路 (upload)
    // ==========================================

    @Test
    @DisplayName("大流上传旁路拦截器内存缓冲: streamingRestClient 绕过拦截器 byte[] 复制")
    void testUploadStreamingBypassesInterceptors() throws InterruptedException {
        AtomicBoolean interceptorCalled = new AtomicBoolean(false);

        // 基于 RestClient.Builder 构建挂载拦截器的 HttpTemplate
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultStatusHandler(new HttpErrorHandler())
                .requestInterceptor((request, body, execution) -> {
                    interceptorCalled.set(true);
                    return execution.execute(request, body);
                });

        HttpTemplate templateWithInterceptor = new HttpTemplate(builder);

        // 1. 普通 GET 请求应正常经过拦截器
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"ok\"}"));

        Map<String, Object> getResult = templateWithInterceptor.get(baseUrl + "/api/ping", Map.class);
        assertThat(getResult).isNotNull();
        assertThat(interceptorCalled.get()).isTrue();
        RecordedRequest pingRecorded = server.takeRequest();
        assertThat(pingRecorded.getMethod()).isEqualTo("GET");

        // 重置拦截器触发标记
        interceptorCalled.set(false);

        // 2. 文件上传请求必须旁路拦截器，直刷底层通道
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"fileId\":\"f-987654321\",\"uploaded\":true}"));

        ByteArrayResource resource = new ByteArrayResource("LARGE-FILE-CONTENT-RAW-BYTES".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "dataset.bin";
            }
        };

        Map<String, Object> uploadResult = templateWithInterceptor.upload(
                baseUrl + "/api/upload",
                "file",
                resource,
                Map.class
        );

        assertThat(uploadResult).isNotNull();
        assertThat(uploadResult.get("fileId")).isEqualTo("f-987654321");

        // 核心断言: 拦截器绝未被触发，彻底杜绝 Spring 内部 ByteArrayOutputStream 全量堆内存缓冲！
        assertThat(interceptorCalled.get()).isFalse();

        // 验证服务端收到完整的 Multipart 请求及流式标记头
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Content-Type")).contains(MediaType.MULTIPART_FORM_DATA_VALUE);
        assertThat(recorded.getHeader(HttpConstants.HEADER_STREAM_TRANSFER)).isEqualTo("true");
        assertThat(recorded.getBody().readUtf8()).contains("LARGE-FILE-CONTENT-RAW-BYTES");
    }

    @Test
    @DisplayName("多分片流式上传: 携带表单字段、文件资源与 RequestOptions 动态选项")
    void testUploadWithMultiValueMapAndOptions() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"code\":200}"));

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("title", "Training Report");
        parts.add("author", "Ateng");

        ByteArrayResource reportResource = new ByteArrayResource("REPORT-CONTENT-DATA".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "report.pdf";
            }
        };
        parts.add("attachment", reportResource);

        RequestOptions options = RequestOptions.builder()
                .header("X-Client-Trace-Id", "trace-abc-123")
                .build();

        Map<String, Object> response = httpTemplate.upload(baseUrl + "/api/v1/documents", parts, options, Map.class);

        assertThat(response).isNotNull();
        assertThat(response.get("success")).isEqualTo(true);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Content-Type")).contains(MediaType.MULTIPART_FORM_DATA_VALUE);
        assertThat(recorded.getHeader("X-Client-Trace-Id")).isEqualTo("trace-abc-123");
        assertThat(recorded.getHeader(HttpConstants.HEADER_STREAM_TRANSFER)).isEqualTo("true");

        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("Training Report");
        assertThat(body).contains("Ateng");
        assertThat(body).contains("REPORT-CONTENT-DATA");
    }
}
