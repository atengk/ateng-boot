package io.github.atengk.boot.sample.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.http.context.HttpRequestContextHolder;
import io.github.atengk.boot.http.error.HttpRemoteException;
import io.github.atengk.boot.http.error.HttpResultCode;
import io.github.atengk.boot.http.interceptor.HttpLoggingInterceptor;
import io.github.atengk.boot.http.options.RequestOptions;
import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import io.github.atengk.boot.http.properties.HttpLoggingLevel;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpRetryProperties;
import io.github.atengk.boot.http.template.HttpTemplate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 1:1 场景沙箱全链路 HTTP 治理端到端集成实测
 * 覆盖内部微服务 Trace/Tenant 穿透、外部非信任域安全隔离、cURL 结构化脱敏日志、4xx/5xx 语义化异常转译及 503 弹性退避重试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootTest
class HttpGovernanceScenarioTest {

    @Autowired
    private HttpTemplate httpTemplate;

    @Autowired
    private HttpProperties httpProperties;

    private MockWebServer mockWebServer;
    private String baseUrl;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger loggingInterceptorLogger;

    private Map<String, HttpClientItemProperties> originalClients;
    private HttpLoggingLevel originalLoggingLevel;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        baseUrl = mockWebServer.url("/").toString();

        // 备份配置，测试后还原
        originalClients = new LinkedHashMap<>(httpProperties.getClients());
        originalLoggingLevel = httpProperties.getLogging().getLevel();

        // 挂载 Logback 内存日志收集器，用于端到端断言拦截器日志输出行为
        loggingInterceptorLogger = (Logger) LoggerFactory.getLogger(HttpLoggingInterceptor.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        loggingInterceptorLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();

        // 严格清理线程与上下文持有者，守卫上下文零污染铁律
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        HttpRequestContextHolder.clear();

        // 恢复配置与卸载日志收集器
        httpProperties.getClients().clear();
        httpProperties.getClients().putAll(originalClients);
        httpProperties.getLogging().setLevel(originalLoggingLevel);

        if (loggingInterceptorLogger != null && listAppender != null) {
            loggingInterceptorLogger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @Test
    @DisplayName("场景 1：内部微服务通信 TraceId、TenantId 与 UserId 透明穿透端到端验证")
    void testInternalMicroserviceContextPropagation() throws InterruptedException {
        // 1. 在当前调用线程装载四维上下文
        MDC.put("traceId", "trace-governance-001");
        TenantContextHolder.setTenantId("tenant-governance-888");
        UserContextHolder.set("user-governance-admin");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"SUCCESS\",\"scope\":\"INTERNAL\"}"));

        // 2. 发起内部微服务调用
        Map<?, ?> response = httpTemplate.get(baseUrl + "internal/user/info", Map.class);
        assertThat(response).isNotNull();
        assertThat(response.get("status")).isEqualTo("SUCCESS");

        // 3. 断言出站请求中安全透传了 Trace、Tenant 与 User 上下文
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("X-Trace-Id")).isEqualTo("trace-governance-001");
        assertThat(request.getHeader("X-Tenant-Id")).isEqualTo("tenant-governance-888");
        assertThat(request.getHeader("X-User-Id")).isEqualTo("user-governance-admin");
        assertThat(request.getHeader("X-Ignore-Tenant")).isNull();

        // 4. 验证在忽略租户环境下异步/同步调用透传 X-Ignore-Tenant: true
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"IGNORED_OK\"}"));

        TenantContextHolder.runWithoutTenant(() -> {
            httpTemplate.get(baseUrl + "internal/system/stats", Map.class);
        });

        RecordedRequest ignoreTenantRequest = mockWebServer.takeRequest();
        assertThat(ignoreTenantRequest.getHeader("X-Trace-Id")).isEqualTo("trace-governance-001");
        assertThat(ignoreTenantRequest.getHeader("X-Tenant-Id")).isEqualTo("tenant-governance-888");
        assertThat(ignoreTenantRequest.getHeader("X-Ignore-Tenant")).isEqualTo("true");
    }

    @Test
    @DisplayName("场景 2：外部微信支付公网通信 URL 最长前缀自适应匹配自动剥离内网私有头验证")
    void testExternalUntrustedDomainContextIsolation() throws InterruptedException {
        // 1. 动态注册外部微信支付客户端配置：指定 baseUrl 前缀并显式禁用上下文透传 (propagateContext = false)
        String wechatPrefix = baseUrl + "wechat-pay/";
        HttpClientItemProperties wechatClient = new HttpClientItemProperties();
        wechatClient.setBaseUrl(wechatPrefix);
        wechatClient.setPropagateContext(false);
        httpProperties.getClients().put("wechat-pay", wechatClient);

        // 2. 当前调用线程装载内部敏感上下文
        MDC.put("traceId", "trace-wechat-order-999");
        TenantContextHolder.setTenantId("tenant-secret-corp");
        UserContextHolder.set("user-vip-operator");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"return_code\":\"SUCCESS\",\"result_code\":\"SUCCESS\"}"));

        // 3. 发起外部微信支付统一预下单请求（URL 命中 wechatPrefix 前缀）
        Map<?, ?> payResult = httpTemplate.post(
                wechatPrefix + "v3/pay/transactions/jsapi",
                Map.of("mchid", "1900000109", "amount", 100),
                Map.class
        );
        assertThat(payResult).isNotNull();

        // 4. 断言微信公网接收到的出站请求：链路追踪 traceId 兜底保留，内网租户与用户信息严格物理剥离
        RecordedRequest wechatRequest = mockWebServer.takeRequest();
        assertThat(wechatRequest.getHeader("X-Trace-Id")).isEqualTo("trace-wechat-order-999");
        assertThat(wechatRequest.getHeader("X-Tenant-Id")).isNull();
        assertThat(wechatRequest.getHeader("X-User-Id")).isNull();
        assertThat(wechatRequest.getHeader("X-Ignore-Tenant")).isNull();

        // 5. 对照验证：非微信前缀的普通内部调用仍保持完整上下文透传
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"INTERNAL_ORDER_OK\"}"));

        httpTemplate.get(baseUrl + "internal/orders/detail", Map.class);
        RecordedRequest internalRequest = mockWebServer.takeRequest();
        assertThat(internalRequest.getHeader("X-Trace-Id")).isEqualTo("trace-wechat-order-999");
        assertThat(internalRequest.getHeader("X-Tenant-Id")).isEqualTo("tenant-secret-corp");
        assertThat(internalRequest.getHeader("X-User-Id")).isEqualTo("user-vip-operator");
    }

    @Test
    @DisplayName("场景 3：敏感 Header（Authorization）动态脱敏与 cURL 命令输出验证（守卫传输真明文与日志脱敏）")
    void testMaskedHeadersAndCurlLoggingReproduce() throws InterruptedException {
        // 开启 FULL 级别日志输出
        httpProperties.getLogging().setLevel(HttpLoggingLevel.FULL);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"auth\":\"passed\"}"));

        RequestOptions options = RequestOptions.builder()
                .header("Authorization", "Bearer top-secret-token-abcdef123456")
                .header("X-Api-Key", "api-secret-key-99999")
                .build();

        // 发起带有敏感凭据的请求
        Map<?, ?> result = httpTemplate.post(
                baseUrl + "secure/api",
                Map.of("action", "query"),
                options,
                Map.class
        );
        assertThat(result).isNotNull();

        // 1. 网络层验证：物理请求必须 100% 保持真实明文（守卫网络传输真明文不变量，绝不给服务器传打星号的数据）
        RecordedRequest physicalRequest = mockWebServer.takeRequest();
        assertThat(physicalRequest.getHeader("Authorization")).isEqualTo("Bearer top-secret-token-abcdef123456");
        assertThat(physicalRequest.getHeader("X-Api-Key")).isEqualTo("api-secret-key-99999");

        // 2. 日志层验证：通过 ListAppender 捕获出站日志，断言敏感 Header 掩码脱敏为 ***，且 cURL 命令中同样输出 ***
        List<String> logMessages = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        boolean curlReproduceFound = logMessages.stream().anyMatch(msg ->
                msg.contains("cURL Reproduce:")
                        && msg.contains("curl -X POST")
                        && msg.contains("-H 'Authorization: Bearer to")
                        && msg.contains("-H 'X-Api-Key: ap")
                        && msg.contains("*************************")
                        && !msg.contains("top-secret-token-abcdef123456")
                        && !msg.contains("api-secret-key-99999")
        );
        assertThat(curlReproduceFound).as("出站日志中应打印完整 cURL 命令并对敏感 Header 实施掩码脱敏，严禁泄露真实明文凭据").isTrue();
    }

    @Test
    @DisplayName("场景 4：远端 404/500/504 返回语义化 HttpRemoteException 并精准映射专职 HttpResultCode")
    void testHttpErrorStatusTranslationToResultCode() {
        // 1. 模拟 404 Not Found
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"User not found\"}"));

        assertThatThrownBy(() -> httpTemplate.get(baseUrl + "users/9999", Map.class))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(404);
                    assertThat(remoteEx.getResultCode()).isEqualTo(HttpResultCode.REMOTE_HTTP_NOT_FOUND);
                    assertThat(remoteEx.getResultCode().getCode()).isEqualTo(40004);
                    assertThat(remoteEx.getResponseBody()).contains("User not found");
                });

        // 2. 模拟 500 Internal Server Error
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Internal database lock\"}"));

        assertThatThrownBy(() -> httpTemplate.get(baseUrl + "db/query", Map.class))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(500);
                    assertThat(remoteEx.getResultCode()).isEqualTo(HttpResultCode.REMOTE_HTTP_SERVER_ERROR);
                    assertThat(remoteEx.getResultCode().getCode()).isEqualTo(50001);
                });

        // 3. 模拟 504 Gateway Timeout
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(504)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Upstream timeout\"}"));

        assertThatThrownBy(() -> httpTemplate.get(baseUrl + "gateway/upstream", Map.class))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(504);
                    assertThat(remoteEx.getResultCode()).isEqualTo(HttpResultCode.REMOTE_HTTP_TIMEOUT);
                    assertThat(remoteEx.getResultCode().getCode()).isEqualTo(50004);
                });
    }

    @Test
    @DisplayName("场景 5：模拟 503 临时抖动 -> 自动指数退避重试 -> 最终 200 成功且内层日志完整记录 3 次调用")
    void testTransient503ExponentialBackoffRetrySuccess() throws InterruptedException {
        // 1. 为集群重试服务配置独立的客户端重试参数（启用重试，最多3次，退避基数30ms）
        String retryPrefix = baseUrl + "cluster-service/";
        HttpClientItemProperties retryClient = new HttpClientItemProperties();
        retryClient.setBaseUrl(retryPrefix);
        HttpRetryProperties retryProps = new HttpRetryProperties();
        retryProps.setEnabled(true);
        retryProps.setMaxAttempts(3);
        retryProps.setDelay(Duration.ofMillis(30));
        retryProps.setMultiplier(1.5);
        retryProps.setMaxDelay(Duration.ofMillis(200));
        retryProps.setJitter(false);
        retryClient.setRetry(retryProps);
        httpProperties.getClients().put("cluster-service", retryClient);

        // 2. MockWebServer 入队 2 次 503 失败，第 3 次返回 200 成功
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Service Unavailable Attempt 1\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Service Unavailable Attempt 2\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"CLUSTER_RECOVERED\",\"attempt\":3}"));

        listAppender.list.clear();

        // 3. 发起幂等 GET 请求
        Map<?, ?> result = httpTemplate.get(retryPrefix + "health", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("status")).isEqualTo("CLUSTER_RECOVERED");
        assertThat(result.get("attempt")).isEqualTo(3);

        // 4. 验证 MockWebServer 真实接收到了 3 次物理 HTTP 请求
        assertThat(mockWebServer.getRequestCount()).isEqualTo(3);

        // 5. 验证内层 HttpLoggingInterceptor 独立记录了 3 次请求日志（证明拦截顺序：Retry 在外层，Logging 在内层）
        long httpLogCount = listAppender.list.stream()
                .filter(event -> event.getLoggerName().contains("HttpLoggingInterceptor"))
                .filter(event -> event.getFormattedMessage().contains("HTTP GET"))
                .count();
        assertThat(httpLogCount).as("内层日志拦截器应完整记录 3 次物理调用（2次503与1次200）").isEqualTo(3);
    }
}
