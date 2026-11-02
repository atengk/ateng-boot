package io.github.atengk.boot.social.http;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.dromara.anduin.http.AdapterHttpResponse;
import org.dromara.anduin.http.HttpConstants;
import org.dromara.anduin.http.HttpHeader;
import org.dromara.anduin.http.UnifiedHttpClientAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpringRestClientHttpImpl 原生 HTTP 适配器离线单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class SpringRestClientHttpImplTest {

    private MockWebServer mockWebServer;
    private SpringRestClientHttpImpl httpAdapter;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        httpAdapter = new SpringRestClientHttpImpl(Duration.ofSeconds(3), Duration.ofSeconds(3), null);
        UnifiedHttpClientAccessor.setAdapter(httpAdapter);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
        MDC.clear();
        UnifiedHttpClientAccessor.reset();
    }

    @Test
    @DisplayName("GET 请求参数拼接与响应解析验证")
    void testGetWithParamsAndHeaders() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"code\":200,\"data\":\"ok\"}"));

        String baseUrl = mockWebServer.url("/oauth/authorize").toString();
        Map<String, String> params = new HashMap<>();
        params.put("client_id", "test_id");
        params.put("scope", "snsapi_userinfo");

        HttpHeader header = new HttpHeader();
        header.add("X-Custom-Auth", "secret-key");

        AdapterHttpResponse response = httpAdapter.get(baseUrl, params, header, true);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(200, response.getCode());
        assertTrue(response.getBody().contains("\"ok\""));

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals("GET", recordedRequest.getMethod());
        assertTrue(recordedRequest.getPath().contains("client_id=test_id"));
        assertTrue(recordedRequest.getPath().contains("scope=snsapi_userinfo"));
        assertEquals("secret-key", recordedRequest.getHeader("X-Custom-Auth"));
        assertEquals(HttpConstants.USER_AGENT_DATA, recordedRequest.getHeader(HttpConstants.USER_AGENT));
    }

    @Test
    @DisplayName("出站请求 MDC X-Trace-Id 自动透传验证")
    void testTraceIdPropagation() throws InterruptedException {
        String testTraceId = "trace-uuid-1234567890";
        MDC.put("traceId", testTraceId);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\"}"));

        String url = mockWebServer.url("/test-trace").toString();
        AdapterHttpResponse response = httpAdapter.get(url);

        assertTrue(response.isSuccess());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals(testTraceId, recordedRequest.getHeader("X-Trace-Id"));
    }

    @Test
    @DisplayName("POST JSON 数据请求验证")
    void testPostJsonData() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"access_token\":\"token_abc\"}"));

        String url = mockWebServer.url("/oauth/token").toString();
        String jsonPayload = "{\"code\":\"auth_code_123\"}";

        AdapterHttpResponse response = httpAdapter.post(url, jsonPayload);

        assertTrue(response.isSuccess());
        assertEquals(200, response.getCode());
        assertTrue(response.getBody().contains("token_abc"));

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals(jsonPayload, recordedRequest.getBody().readUtf8());
        assertEquals(HttpConstants.CONTENT_TYPE_JSON, recordedRequest.getHeader("Content-Type"));
    }

    @Test
    @DisplayName("POST 表单请求 (postForm) 与参数 URL 编码验证")
    void testPostForm() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"openid\":\"wx_openid_999\"}"));

        String url = mockWebServer.url("/sns/oauth2/access_token").toString();
        Map<String, String> formParams = new HashMap<>();
        formParams.put("appid", "wx123456");
        formParams.put("secret", "sec888");
        formParams.put("code", "code_xyz+123==");

        HttpHeader header = new HttpHeader();
        header.add("X-Request-Source", "Wechat");

        AdapterHttpResponse response = httpAdapter.postForm(url, formParams, header);

        assertTrue(response.isSuccess());
        assertEquals(200, response.getCode());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertEquals("POST", recordedRequest.getMethod());
        assertEquals("application/x-www-form-urlencoded", recordedRequest.getHeader("Content-Type"));
        assertEquals("Wechat", recordedRequest.getHeader("X-Request-Source"));

        String requestBody = recordedRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("appid=wx123456"));
        assertTrue(requestBody.contains("secret=sec888"));
        assertTrue(requestBody.contains("code="));
    }

    @Test
    @DisplayName("第三方 401 失败响应优雅封装验证（不向外抛出未受控崩溃异常）")
    void testErrorResponseHandling() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"errcode\":40029,\"errmsg\":\"invalid code\"}"));

        String url = mockWebServer.url("/sns/userinfo").toString();
        AdapterHttpResponse response = httpAdapter.get(url);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(401, response.getCode());
        assertTrue(response.getBody().contains("invalid code"));
    }

    @Test
    @DisplayName("UnifiedHttpClientAccessor 注册与全局注入验证")
    void testUnifiedHttpClientAccessor() {
        assertSame(httpAdapter, UnifiedHttpClientAccessor.getAdapter());
        UnifiedHttpClientAccessor.reset();
        assertNull(UnifiedHttpClientAccessor.getAdapter());
    }

    @Test
    @DisplayName("SocialProxyConfig 原生代理模型推导验证")
    void testProxyConfig() {
        SocialProxyConfig validConfig = SocialProxyConfig.builder()
                .type(Proxy.Type.HTTP)
                .host("127.0.0.1")
                .port(7890)
                .build();
        Proxy proxy = validConfig.toProxy();
        assertNotNull(proxy);
        assertEquals(Proxy.Type.HTTP, proxy.type());

        SocialProxyConfig invalidConfig = SocialProxyConfig.builder()
                .host("")
                .port(0)
                .build();
        assertNull(invalidConfig.toProxy());
    }
}
