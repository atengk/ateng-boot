package io.github.atengk.boot.sample.http;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.http.context.HttpRequestContextHolder;
import io.github.atengk.boot.http.error.HttpRemoteException;
import io.github.atengk.boot.http.error.HttpResultCode;
import io.github.atengk.boot.http.options.RequestOptions;
import io.github.atengk.boot.http.template.HttpTemplate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

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
        try {
            if (mockWebServer != null) {
                mockWebServer.shutdown();
            }
        } finally {
            MDC.clear();
            UserContextHolder.clear();
            TenantContextHolder.clear();
            HttpRequestContextHolder.clear();
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
                    assertThat(remoteEx.getResultCode()).isEqualTo(HttpResultCode.REMOTE_HTTP_NOT_FOUND);
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
                    assertThat(remoteEx.getResultCode()).isEqualTo(HttpResultCode.REMOTE_HTTP_SERVER_ERROR);
                    assertThat(remoteEx.getResponseBody()).isNotNull();
                    assertThat(remoteEx.getResponseBody()).endsWith("... [truncated]");
                    assertThat(remoteEx.getResponseBody()).hasSize(2048 + "... [truncated]".length());
                });
    }

    @Test
    @DisplayName("测试 RequestOptions 注入自定义 Header 与安全 RFC 3986 编码 Query 参数")
    void testRequestOptionsHeadersAndQueryParamsWithRfc3986() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"OK\"}"));

        RequestOptions options = RequestOptions.builder()
                .header("X-Tenant-Id", "tenant-vip-001")
                .header("X-Client-Version", "1.0.0")
                .queryParam("keyword", "深度 学习")
                .queryParam("tag", "ai+model")
                .build();

        Map<?, ?> result = httpTemplate.get(baseUrl + "search", options, Map.class);
        assertThat(result).isNotNull();

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("X-Tenant-Id")).isEqualTo("tenant-vip-001");
        assertThat(request.getHeader("X-Client-Version")).isEqualTo("1.0.0");
        assertThat(request.getPath())
                .contains("keyword=%E6%B7%B1%E5%BA%A6%20%E5%AD%A6%E4%B9%A0")
                .contains("tag=ai+model");
    }

    @Test
    @DisplayName("测试 RequestOptions 注入 URI 模板变量并正确完成路径展开")
    void testRequestOptionsUriTemplateVariables() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"userId\":6666,\"role\":\"ADMIN\"}"));

        RequestOptions options = RequestOptions.builder()
                .uriVariable("deptId", "rd")
                .uriVariable("userId", 6666)
                .queryParam("detail", true)
                .build();

        Map<?, ?> result = httpTemplate.get(
                baseUrl + "departments/{deptId}/users/{userId}",
                options,
                Map.class
        );
        assertThat(result).isNotNull();

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/departments/rd/users/6666?detail=true");
    }

    @Test
    @DisplayName("测试 RequestOptions 单次读取超时覆盖生效（触发快速失败）")
    void testRequestOptionsPerRequestTimeoutOverride() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"delayed\":true}")
                .setHeadersDelay(2, TimeUnit.SECONDS));

        RequestOptions options = RequestOptions.builder()
                .readTimeout(Duration.ofMillis(300))
                .build();

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> httpTemplate.get(baseUrl + "delayed-endpoint", options, Map.class))
                .isInstanceOf(ResourceAccessException.class);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(1500);
    }

    @Test
    @DisplayName("测试大文件流式下载至本地文件（8KB 零缓冲直刷与临时目录隔离）")
    void testDownloadLargeFileStream(@TempDir Path tempDir) throws IOException {
        // 构造 64KB 重复数据
        byte[] expectedContent = new byte[65536];
        Arrays.fill(expectedContent, (byte) 'A');

        Buffer buffer = new Buffer();
        buffer.write(expectedContent);
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(buffer));

        Path targetFile = tempDir.resolve("downloaded-file.bin");
        httpTemplate.download(baseUrl + "download", targetFile);

        assertThat(Files.exists(targetFile)).isTrue();
        assertThat(Files.size(targetFile)).isEqualTo(65536);
        assertThat(Files.readAllBytes(targetFile)).isEqualTo(expectedContent);

        // 验证 404 时抛出 HttpRemoteException
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"file not found\"}"));

        Path failFile = tempDir.resolve("fail-file.bin");
        assertThatThrownBy(() -> httpTemplate.download(baseUrl + "download/not-found", failFile))
                .isInstanceOf(HttpRemoteException.class);
        // 验证失败中断时自动执行补偿清理，绝不残留 0 字节或损坏的孤儿文件
        assertThat(Files.exists(failFile)).isFalse();
    }

    @Test
    @DisplayName("测试流式 Multipart 文件上传")
    void testUploadMultipartFile() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"filename\":\"report.csv\"}"));

        byte[] fileBytes = "id,name,role\n1,ateng,admin".getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new NamedByteArrayResource(fileBytes, "report.csv");

        Map<?, ?> result = httpTemplate.upload(baseUrl + "upload", "file", resource, Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("success")).isEqualTo(true);

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getHeader("Content-Type")).contains("multipart/form-data");
        assertThat(recordedRequest.getBody().readUtf8()).contains("report.csv");
    }

    @Test
    @DisplayName("测试 executeAsync 异步执行与四维上下文（MDC、User、Tenant、IgnoreTenant）跨线程透传")
    void testExecuteAsyncAndFourDimensionalContextPropagation() throws ExecutionException, InterruptedException, TimeoutException {
        // 1. 在当前线程装载四维上下文
        MDC.put("traceId", "trace-async-9999");
        UserContextHolder.set("user-async-super");
        TenantContextHolder.setTenantId("tenant-async-001");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"async\":\"OK\"}"));

        // 2. 在忽略租户环境下异步执行，验证在工作线程中四维上下文成功透传
        CompletableFuture<Map> future = TenantContextHolder.runWithoutTenant(() ->
                httpTemplate.executeAsync(() -> {
                    assertThat(MDC.get("traceId")).isEqualTo("trace-async-9999");
                    assertThat(UserContextHolder.get()).isEqualTo("user-async-super");
                    assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-async-001");
                    assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
                    return httpTemplate.get(baseUrl + "async-call", Map.class);
                })
        );

        Map<?, ?> result = future.get(5, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.get("async")).isEqualTo("OK");
    }

    @Test
    @DisplayName("测试 50 并发请求下的连接池物理连接复用与高并发稳定准出")
    void testFiftyConcurrentRequestsConnectionPoolReuse() throws ExecutionException, InterruptedException, TimeoutException {
        int requestCount = 50;
        for (int i = 0; i < requestCount; i++) {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"seq\":" + i + "}"));
        }

        List<CompletableFuture<Map<String, Object>>> futures = IntStream.range(0, requestCount)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> httpTemplate.get(
                        baseUrl + "pool-load",
                        new ParameterizedTypeReference<Map<String, Object>>() {}
                )))
                .toList();

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allFutures.get(10, TimeUnit.SECONDS);

        assertThat(futures).allMatch(f -> {
            try {
                return f.get() != null && f.get().containsKey("seq");
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    @DisplayName("测试流式 Multipart 上传并基于 ParameterizedTypeReference 复杂泛型反序列化")
    void testUploadMultipartWithParameterizedTypeReference() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"msg\":\"操作成功\",\"data\":{\"fileId\":9988,\"name\":\"contract.pdf\"}}"));

        byte[] fileBytes = "%PDF-1.4 dummy content".getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new NamedByteArrayResource(fileBytes, "contract.pdf");

        Result<Map<String, Object>> result = httpTemplate.upload(
                baseUrl + "upload-generic",
                "document",
                resource,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("fileId")).isEqualTo(9988);

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getHeader("Content-Type")).contains("multipart/form-data");
    }

    @Test
    @DisplayName("测试 HttpClient5Customizer 与 RestClientCustomizer 容器扩展生效")
    void testCustomizersApplied() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"customizer\":\"OK\"}"));

        Map<?, ?> result = httpTemplate.get(baseUrl + "customizer-verify", Map.class);
        assertThat(result).isNotNull();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getHeader("X-Custom-Client")).isEqualTo("ateng-http-sample");
        assertThat(recordedRequest.getHeader("User-Agent")).isEqualTo("ateng-sample-agent/1.0.0");
    }

    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return this.filename;
        }
    }
}
