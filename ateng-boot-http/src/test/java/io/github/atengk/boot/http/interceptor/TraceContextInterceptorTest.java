package io.github.atengk.boot.http.interceptor;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.http.constant.HttpConstants;
import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
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
}
