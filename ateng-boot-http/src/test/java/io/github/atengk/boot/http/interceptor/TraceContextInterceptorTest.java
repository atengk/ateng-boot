package io.github.atengk.boot.http.interceptor;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.http.constant.HttpConstants;
import io.github.atengk.boot.http.context.HttpRequestContextHolder;
import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import io.github.atengk.boot.http.properties.HttpLoggingLevel;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpRetryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 出站四维上下文透传与非信任域隔离拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class TraceContextInterceptorTest {

    private HttpProperties properties;
    private TraceContextInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new HttpProperties();
        // 配置外部非信任客户端：微信支付 (propagateContext = false)
        HttpClientItemProperties wechat = new HttpClientItemProperties();
        wechat.setBaseUrl("https://api.mch.weixin.qq.com");
        wechat.setPropagateContext(false);
        properties.getClients().put("wechat-pay", wechat);

        // 配置内部微服务客户端：用户服务 (propagateContext = true)
        HttpClientItemProperties userService = new HttpClientItemProperties();
        userService.setBaseUrl("http://192.168.1.100:8080");
        userService.setPropagateContext(true);
        properties.getClients().put("user-service", userService);

        interceptor = new TraceContextInterceptor(properties);
    }

    @AfterEach
    void tearDown() {
        // 恪守单测三项铁律：上下文零污染，测试完毕后严格物理重置
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        HttpRequestContextHolder.clear();
    }

    @Test
    @DisplayName("内部微服务调用：四维上下文 (TraceId, TenantId, UserId, IgnoreTenant) 自动透明透传")
    void shouldPropagate4DContextToInternalService() throws IOException {
        MDC.put("traceId", "trace-parent-999");
        TenantContextHolder.setTenantId("tenant-1001");
        UserContextHolder.setUserId("user-8888");

        TenantContextHolder.runWithoutTenant(() -> {
            try {
                MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://192.168.1.100:8080/users/profile"));
                AtomicReference<HttpHeaders> capturedHeaders = new AtomicReference<>();

                ClientHttpRequestExecution execution = (req, body) -> {
                    capturedHeaders.set(req.getHeaders());
                    return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
                };

                interceptor.intercept(request, new byte[0], execution);

                HttpHeaders headers = capturedHeaders.get();
                assertNotNull(headers);
                assertEquals("trace-parent-999", headers.getFirst("X-Trace-Id"));
                assertEquals("tenant-1001", headers.getFirst("X-Tenant-Id"));
                assertEquals("true", headers.getFirst("X-Ignore-Tenant"));
                assertEquals("user-8888", headers.getFirst("X-User-Id"));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    @Test
    @DisplayName("MDC 为空时：自动通过 IdUtil 生成兜底 32 位短 UUID，保障 TraceId 永不中断")
    void shouldGenerateFallbackTraceIdWhenMdcIsEmpty() throws IOException {
        // 确保 MDC 为空
        MDC.clear();

        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://192.168.1.100:8080/health"));
        AtomicReference<HttpHeaders> capturedHeaders = new AtomicReference<>();

        ClientHttpRequestExecution execution = (req, body) -> {
            capturedHeaders.set(req.getHeaders());
            return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
        };

        interceptor.intercept(request, new byte[0], execution);

        HttpHeaders headers = capturedHeaders.get();
        assertNotNull(headers);
        String generatedTraceId = headers.getFirst("X-Trace-Id");
        assertNotNull(generatedTraceId);
        assertEquals(32, generatedTraceId.length(), "自动生成的短 UUID 应为 32 位字符");
    }

    @Test
    @DisplayName("外部非信任域 (微信支付)：URL 最长前缀匹配自动隔离，严格剥离内网私有敏感头")
    void shouldIsolateExternalDomainAndStripSensitiveHeaders() throws IOException {
        MDC.put("traceId", "trace-wechat-call");
        TenantContextHolder.setTenantId("tenant-secret");
        UserContextHolder.setUserId("user-secret");

        TenantContextHolder.runWithoutTenant(() -> {
            try {
                MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi"));
                // 模拟调用方误将内网敏感头预先塞入
                request.getHeaders().add("X-Tenant-Id", "tenant-leaked");
                request.getHeaders().add("X-User-Id", "user-leaked");
                request.getHeaders().add("X-Ignore-Tenant", "true");

                AtomicReference<HttpHeaders> capturedHeaders = new AtomicReference<>();
                ClientHttpRequestExecution execution = (req, body) -> {
                    capturedHeaders.set(req.getHeaders());
                    return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
                };

                interceptor.intercept(request, new byte[0], execution);

                HttpHeaders headers = capturedHeaders.get();
                assertNotNull(headers);
                // TraceId 保留以备全链路追踪
                assertEquals("trace-wechat-call", headers.getFirst("X-Trace-Id"));
                // 租户与用户标识必须被 100% 物理剥离清洗
                assertNull(headers.getFirst("X-Tenant-Id"), "外部请求严禁泄露租户标识");
                assertNull(headers.getFirst("X-User-Id"), "外部请求严禁泄露用户标识");
                assertNull(headers.getFirst("X-Ignore-Tenant"), "外部请求严禁泄露忽略租户标识");
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    @Test
    @DisplayName("RequestOptions 单次动态覆盖：支持显式透传控制与内部标记头出站前安全清除")
    void shouldSupportRequestOptionsDynamicOverride() throws IOException {
        TenantContextHolder.setTenantId("tenant-override");
        UserContextHolder.setUserId("user-override");

        // 场景 1: 本是外部微信接口，但显式覆盖 propagateContext = true
        MockClientHttpRequest req1 = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.mch.weixin.qq.com/v3/custom"));
        req1.getHeaders().add(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE, "true");

        AtomicReference<HttpHeaders> capturedHeaders1 = new AtomicReference<>();
        interceptor.intercept(req1, new byte[0], (req, body) -> {
            capturedHeaders1.set(req.getHeaders());
            return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
        });

        assertEquals("tenant-override", capturedHeaders1.get().getFirst("X-Tenant-Id"));
        assertNull(capturedHeaders1.get().getFirst(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE), "内部控制头必须已被安全剥离");

        // 场景 2: 本是内部用户服务，但显式覆盖 propagateContext = false
        MockClientHttpRequest req2 = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://192.168.1.100:8080/users/info"));
        req2.getHeaders().add(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE, "false");

        AtomicReference<HttpHeaders> capturedHeaders2 = new AtomicReference<>();
        interceptor.intercept(req2, new byte[0], (req, body) -> {
            capturedHeaders2.set(req.getHeaders());
            return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
        });

        assertNull(capturedHeaders2.get().getFirst("X-Tenant-Id"), "显式关闭透传后应被严格剥离");
        assertNull(capturedHeaders2.get().getFirst(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE), "内部控制头必须已被安全剥离");
    }

    @Test
    @DisplayName("RequestOptions 显式指定 clientName：直接匹配客户端专属策略")
    void shouldMatchStrategyByExplicitClientName() throws IOException {
        TenantContextHolder.setTenantId("tenant-by-name");
        UserContextHolder.setUserId("user-by-name");

        // URL 虽为通用域名，但显式指定了 wechat-pay 客户端名称
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://custom.gateway.com/pay"));
        request.getHeaders().add(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE, "wechat-pay");

        AtomicReference<HttpHeaders> capturedHeaders = new AtomicReference<>();
        interceptor.intercept(request, new byte[0], (req, body) -> {
            capturedHeaders.set(req.getHeaders());
            return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
        });

        assertNull(capturedHeaders.get().getFirst("X-Tenant-Id"), "绑定外部客户端后应被严格剥离");
        assertNull(capturedHeaders.get().getFirst(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE), "内部客户端名称控制头必须已被剥离");
    }

    @Test
    @DisplayName("自定义出站 Header 键名：遵循强类型配置灵活重命名")
    void shouldSupportCustomHeaderNames() throws IOException {
        MDC.put("traceId", "trace-custom-key");
        TenantContextHolder.setTenantId("tenant-custom-key");
        UserContextHolder.setUserId("user-custom-key");

        properties.getTracing().setTraceIdHeader("Custom-Trace-Id");
        properties.getTracing().setTenantIdHeader("Custom-Tenant-Id");
        properties.getTracing().setUserIdHeader("Custom-User-Id");
        properties.getTracing().setIgnoreTenantHeader("Custom-Ignore-Tenant");

        TenantContextHolder.runWithoutTenant(() -> {
            try {
                MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://192.168.1.100:8080/test"));
                AtomicReference<HttpHeaders> capturedHeaders = new AtomicReference<>();

                interceptor.intercept(request, new byte[0], (req, body) -> {
                    capturedHeaders.set(req.getHeaders());
                    return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
                });

                HttpHeaders headers = capturedHeaders.get();
                assertEquals("trace-custom-key", headers.getFirst("Custom-Trace-Id"));
                assertEquals("tenant-custom-key", headers.getFirst("Custom-Tenant-Id"));
                assertEquals("user-custom-key", headers.getFirst("Custom-User-Id"));
                assertEquals("true", headers.getFirst("Custom-Ignore-Tenant"));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    @Test
    @DisplayName("开关关闭时 (tracing.enabled = false)：直接放行不注入出站头，但内部私有控制头依然必须严格剥离")
    void shouldBypassWhenTracingIsDisabled() throws IOException {
        properties.getTracing().setEnabled(false);
        MDC.put("traceId", "trace-ignored");
        TenantContextHolder.setTenantId("tenant-ignored");

        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://192.168.1.100:8080/test"));
        request.getHeaders().add(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE, "false");
        request.getHeaders().add(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE, "my-client");
        AtomicReference<HttpHeaders> capturedHeaders = new AtomicReference<>();

        interceptor.intercept(request, new byte[0], (req, body) -> {
            capturedHeaders.set(req.getHeaders());
            return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
        });

        HttpHeaders headers = capturedHeaders.get();
        assertNull(headers.getFirst("X-Trace-Id"));
        assertNull(headers.getFirst("X-Tenant-Id"));
        assertNull(headers.getFirst(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE), "关闭追踪时内部透传控制头必须被严格剥离");
        assertNull(headers.getFirst(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE), "关闭追踪时内部客户端控制头必须被严格剥离");
    }

    @Test
    @DisplayName("纯头部治理方法：支持无流缓冲直通路径执行受信域名判定、四维头注入/剥离与内部控制头全净空")
    void shouldGovernHeadersDirectlyWithoutBuffering() {
        MDC.put("traceId", "trace-direct-001");
        TenantContextHolder.setTenantId("tenant-direct");
        UserContextHolder.setUserId("user-direct");

        // 1. 内部信任域场景：注入四维头并清空全量 X-Ateng-Internal-* 控制头
        HttpHeaders internalHeaders = new HttpHeaders();
        internalHeaders.set(HttpConstants.HEADER_STREAM_TRANSFER, "true");
        internalHeaders.set(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE, "user-service");
        internalHeaders.set(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE, "true");

        TenantContextHolder.runWithoutTenant(() -> {
            interceptor.applyContextHeaders(internalHeaders, URI.create("http://192.168.1.100:8080/upload"));
        });

        assertEquals("trace-direct-001", internalHeaders.getFirst("X-Trace-Id"));
        assertEquals("tenant-direct", internalHeaders.getFirst("X-Tenant-Id"));
        assertEquals("user-direct", internalHeaders.getFirst("X-User-Id"));
        assertEquals("true", internalHeaders.getFirst("X-Ignore-Tenant"));
        assertNull(internalHeaders.getFirst(HttpConstants.HEADER_STREAM_TRANSFER));
        assertNull(internalHeaders.getFirst(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE));
        assertNull(internalHeaders.getFirst(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE));

        // 2. 外部非信任客户端场景（clientName 绑定 wechat-pay）：剥离敏感业务头及全量内部控制头
        HttpHeaders untrustedHeaders = new HttpHeaders();
        untrustedHeaders.set("X-Tenant-Id", "tenant-leak");
        untrustedHeaders.set("X-User-Id", "user-leak");
        untrustedHeaders.set("X-Ignore-Tenant", "true");
        untrustedHeaders.set(HttpConstants.HEADER_STREAM_TRANSFER, "true");
        untrustedHeaders.set(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE, "wechat-pay");

        interceptor.applyContextHeaders(untrustedHeaders, URI.create("https://custom.gateway.com/upload"));

        assertEquals("trace-direct-001", untrustedHeaders.getFirst("X-Trace-Id"));
        assertNull(untrustedHeaders.getFirst("X-Tenant-Id"));
        assertNull(untrustedHeaders.getFirst("X-User-Id"));
        assertNull(untrustedHeaders.getFirst("X-Ignore-Tenant"));
        assertNull(untrustedHeaders.getFirst(HttpConstants.HEADER_STREAM_TRANSFER));
        assertNull(untrustedHeaders.getFirst(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE));
        assertNull(untrustedHeaders.getFirst(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE));

        // 3. tracing 关闭时仍须清空全量内部控制头
        properties.getTracing().setEnabled(false);
        HttpHeaders disabledHeaders = new HttpHeaders();
        disabledHeaders.set(HttpConstants.HEADER_STREAM_TRANSFER, "true");
        disabledHeaders.set(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE, "user-service");
        disabledHeaders.set(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE, "false");
        interceptor.applyContextHeaders(disabledHeaders, URI.create("http://192.168.1.100:8080/upload"));

        assertNull(disabledHeaders.getFirst("X-Trace-Id"));
        assertNull(disabledHeaders.getFirst(HttpConstants.HEADER_STREAM_TRANSFER));
        assertNull(disabledHeaders.getFirst(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE));
        assertNull(disabledHeaders.getFirst(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE));
    }

    @Test
    @DisplayName("跨拦截器桥接链路：Trace 提取剥离 clientName 后写入上下文驱动 Retry，cURL 无内部头，结束后还原快照")
    void shouldBridgeClientNameToRetryStripInternalHeadersBeforeCurlAndRestoreOnCompletion() throws IOException {
        HttpClientItemProperties clientA = new HttpClientItemProperties();
        clientA.setBaseUrl("https://shared.gateway.com");
        HttpRetryProperties retryA = new HttpRetryProperties();
        retryA.setEnabled(true);
        retryA.setMaxAttempts(2);
        retryA.setDelay(Duration.ofMillis(5));
        clientA.setRetry(retryA);
        properties.getClients().put("shared-a", clientA);

        HttpClientItemProperties clientB = new HttpClientItemProperties();
        clientB.setBaseUrl("https://shared.gateway.com");
        clientB.setPropagateContext(false);
        HttpRetryProperties retryB = new HttpRetryProperties();
        retryB.setEnabled(true);
        retryB.setMaxAttempts(4);
        retryB.setDelay(Duration.ofMillis(5));
        clientB.setRetry(retryB);
        properties.getClients().put("shared-b", clientB);

        properties.getLogging().setEnabled(true);
        properties.getLogging().setLevel(HttpLoggingLevel.FULL);
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(properties);
        HttpRetryInterceptor retryInterceptor = new HttpRetryInterceptor(properties, loggingInterceptor);

        HttpRequestContextHolder.setClientName("outer-client");

        // 场景 1：正常完成重试命中 shared-b (4 次)，cURL 与下游请求头均无 X-Ateng-Internal-*
        MockClientHttpRequest req1 = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://shared.gateway.com/api/data"));
        req1.getHeaders().add(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE, "shared-b");
        req1.getHeaders().add(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE, "false");
        req1.getHeaders().add(HttpConstants.HEADER_STREAM_TRANSFER, "true");

        AtomicInteger attempts1 = new AtomicInteger(0);
        AtomicReference<String> generatedCurl = new AtomicReference<>();
        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();

        ClientHttpRequestExecution chain = (req, body) -> retryInterceptor.intercept(req, body, (r, b) -> {
            attempts1.incrementAndGet();
            downstreamHeaders.set(r.getHeaders());
            generatedCurl.set(loggingInterceptor.generateCurlCommand(r, b, properties.getLogging()));
            return new MockClientHttpResponse(new byte[0], HttpStatus.SERVICE_UNAVAILABLE);
        });

        ClientHttpResponse resp = interceptor.intercept(req1, new byte[0], chain);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals(4, attempts1.get(), "TraceContextInterceptor 桥接后的 HttpRetryInterceptor 应命中 shared-b 的 maxAttempts=4");
        assertNotNull(generatedCurl.get());
        assertFalse(generatedCurl.get().contains("X-Ateng-Internal-"), "cURL 重放命令中不得出现任何 X-Ateng-Internal-* 内部控制头");
        assertNull(downstreamHeaders.get().getFirst(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE));
        assertNull(downstreamHeaders.get().getFirst(HttpConstants.HEADER_PROPAGATE_CONTEXT_OVERRIDE));
        assertNull(downstreamHeaders.get().getFirst(HttpConstants.HEADER_STREAM_TRANSFER));
        assertEquals("outer-client", HttpRequestContextHolder.getClientName(), "请求完成后须精准还原进入前的 clientName 快照");

        // 场景 2：底层持续抛出 IOException 耗尽重试并向外抛出异常时，依然还原 clientName 快照
        HttpRequestContextHolder.clear();
        MockClientHttpRequest req2 = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://shared.gateway.com/api/down"));
        req2.getHeaders().add(HttpConstants.HEADER_CLIENT_NAME_OVERRIDE, "shared-b");

        assertThrows(IOException.class, () -> interceptor.intercept(req2, new byte[0],
                (req, body) -> retryInterceptor.intercept(req, body, (r, b) -> {
                    throw new IOException("connection reset");
                })));
        assertNull(HttpRequestContextHolder.getClientName(), "异常结束路径后 clientName 上下文必须清空无残留");
    }
}
