package io.github.atengk.boot.http.factory;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.http.annotation.EnableHttpInterfaces;
import io.github.atengk.boot.http.annotation.HttpClient;
import io.github.atengk.boot.http.autoconfigure.AtengHttpAutoConfiguration;
import io.github.atengk.boot.http.constant.HttpConstants;
import io.github.atengk.boot.http.error.HttpErrorHandler;
import io.github.atengk.boot.http.error.HttpRemoteException;
import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpProxyProperties;
import io.github.atengk.boot.http.properties.HttpRetryProperties;
import io.github.atengk.boot.http.properties.HttpSslProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.RestClient;
import org.springframework.web.service.annotation.GetExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RestClientRegistry 多命名客户端注册表与单例管理器单元测试
 * 覆盖单例缓存并发安全性、BaseUrl 多级覆盖与占位符解析、客户端配置隔离与端到端通信
 *
 * @author Ateng
 * @since 2026-10-21
 */
class RestClientRegistryTest {

    private MockWebServer mockWebServer;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        if (context != null) {
            context.close();
            context = null;
        }
        if (mockWebServer != null) {
            mockWebServer.shutdown();
            mockWebServer = null;
        }
    }

    @Test
    @DisplayName("测试单例缓存与并发安全：相同 clientName 返回同一单例，具备完备管理语义")
    void testSingletonCacheAndConcurrency() throws Exception {
        HttpProperties properties = new HttpProperties();
        MockEnvironment env = new MockEnvironment();
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        HttpErrorHandler errorHandler = new HttpErrorHandler();

        RestClientRegistry registry = new RestClientRegistry(builder, factory, properties, errorHandler, env);

        // 1. 同一线程多次获取，返回同一引用
        RestClient client1 = registry.getClient("order-service", "http://order.local");
        RestClient client2 = registry.getClient("order-service", "http://order.local");
        assertSame(client1, client2, "相同 clientName 必须返回同一个 RestClient 实例引用");
        assertTrue(registry.contains("order-service"));
        assertEquals(1, registry.size());
        assertEquals(Set.of("order-service"), registry.getClientNames());

        // 2. 多线程高并发获取同一 clientName，保证单例性与线程安全
        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<RestClient>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    startLatch.await();
                    return registry.getClient("concurrent-service", "http://concurrent.local");
                }));
            }
            startLatch.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            RestClient firstInstance = futures.get(0).get();
            for (Future<RestClient> future : futures) {
                assertSame(firstInstance, future.get(), "并发调用获取到的命名客户端必须为同一实例");
            }
            assertEquals(2, registry.size());
        } finally {
            pool.shutdownNow();
        }

        // 3. 验证管理语义：register, unregister, clear
        RestClient customClient = RestClient.builder().build();
        registry.register("custom-client", customClient);
        assertSame(customClient, registry.getClient("custom-client"));
        assertEquals(3, registry.size());

        assertTrue(registry.unregister("custom-client"));
        assertFalse(registry.contains("custom-client"));

        registry.clear();
        assertEquals(0, registry.size());
        assertFalse(registry.contains("order-service"));
    }

    @Test
    @DisplayName("测试 BaseUrl 多级覆盖与占位符解析：配置 > 注解 > 全局兜底")
    void testBaseUrlHierarchyAndPlaceholderResolution() {
        HttpProperties properties = new HttpProperties();
        HttpClientItemProperties configItem = new HttpClientItemProperties();
        configItem.setBaseUrl("http://configured.local:8080");
        properties.getClients().put("config-priority-service", configItem);

        MockEnvironment env = new MockEnvironment();
        env.setProperty("remote.service.host", "http://resolved.placeholder.local");

        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        HttpErrorHandler errorHandler = new HttpErrorHandler();

        RestClientRegistry registry = new RestClientRegistry(builder, factory, properties, errorHandler, env);

        // 场景 1：配置文件存在 base-url，优先于注解指定的 url
        String url1 = registry.resolveBaseUrl("config-priority-service", "http://annotation.local:9090");
        assertEquals("http://configured.local:8080", url1, "配置文件中定义的 base-url 必须优先于注解 url");

        // 场景 2：配置文件未指定 base-url，回退使用注解 url
        String url2 = registry.resolveBaseUrl("annotation-service", "http://annotation.local:9090");
        assertEquals("http://annotation.local:9090", url2, "配置文件未定义时回退使用注解 url");

        // 场景 3：注解 url 带有占位符，支持动态解析
        String url3 = registry.resolveBaseUrl("placeholder-service", "${remote.service.host}/api");
        assertEquals("http://resolved.placeholder.local/api", url3, "注解 url 包含占位符时应被正确解析");

        // 场景 4：两处均未指定，返回空字符串
        String url4 = registry.resolveBaseUrl("empty-service", null);
        assertEquals("", url4, "均未配置时返回空字符串");
    }

    @Test
    @DisplayName("测试客户端专属配置隔离：超时隔离、上下文透传隔离与重试隔离")
    void testClientScopedConfigurationIsolation() throws IOException {
        HttpProperties properties = new HttpProperties();

        // 客户端 1：配置专属超时与非信任域（禁止透传业务上下文）
        HttpClientItemProperties client1Props = new HttpClientItemProperties();
        client1Props.setBaseUrl("http://wechat.api");
        client1Props.setReadTimeout(Duration.ofSeconds(3));
        client1Props.setConnectTimeout(Duration.ofSeconds(1));
        client1Props.setPropagateContext(false);
        properties.getClients().put("wechat-client", client1Props);

        // 客户端 2：配置专属重试策略
        HttpClientItemProperties client2Props = new HttpClientItemProperties();
        client2Props.setBaseUrl("http://order.api");
        HttpRetryProperties retryProps = new HttpRetryProperties();
        retryProps.setMaxAttempts(5);
        retryProps.setDelay(Duration.ofMillis(200));
        client2Props.setRetry(retryProps);
        properties.getClients().put("order-client", client2Props);

        MockEnvironment env = new MockEnvironment();
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        HttpErrorHandler errorHandler = new HttpErrorHandler();

        RestClientRegistry registry = new RestClientRegistry(builder, factory, properties, errorHandler, env);

        // 1. 验证初始化 wechat-client 并检验 ClientScopedHttpRequestFactory
        RestClient wechatClient = registry.getClient("wechat-client");
        assertNotNull(wechatClient);

        ClientScopedHttpRequestFactory scopedFactory = new ClientScopedHttpRequestFactory(
                factory,
                Duration.ofSeconds(3),
                Duration.ofSeconds(1)
        );
        assertEquals(Duration.ofSeconds(3), scopedFactory.getReadTimeout());
        assertEquals(Duration.ofSeconds(1), scopedFactory.getConnectTimeout());
        assertSame(factory, scopedFactory.getDelegate());

        // 验证 scopedFactory 创建请求过程
        ClientHttpRequest request = scopedFactory.createRequest(URI.create("http://wechat.api/test"), HttpMethod.GET);
        assertNotNull(request);

        // 2. 验证初始化 order-client 与普通无特殊配置客户端
        RestClient orderClient = registry.getClient("order-client");
        assertNotNull(orderClient);

        RestClient normalClient = registry.getClient("normal-client");
        assertNotNull(normalClient);
    }

    @Test
    @DisplayName("测试结合 MockWebServer 的端到端调用与异常响应转译")
    void testEndToEndCommunicationWithMockWebServer() {
        String baseUrl = mockWebServer.url("/").toString();

        HttpProperties properties = new HttpProperties();
        HttpClientItemProperties item = new HttpClientItemProperties();
        item.setBaseUrl(baseUrl);
        properties.getClients().put("test-service", item);

        MockEnvironment env = new MockEnvironment();
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        HttpErrorHandler errorHandler = new HttpErrorHandler();

        RestClientRegistry registry = new RestClientRegistry(builder, factory, properties, errorHandler, env);
        RestClient client = registry.getClient("test-service");

        // 1. 成功请求实测
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"code\":200,\"msg\":\"success\"}"));

        String responseBody = client.get()
                .uri("/items/101")
                .retrieve()
                .body(String.class);

        assertEquals("{\"code\":200,\"msg\":\"success\"}", responseBody);

        // 2. 异常响应转译实测（HttpErrorHandler 转译为 HttpRemoteException）
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"code\":500,\"message\":\"Internal Error\"}"));

        HttpRemoteException ex = assertThrows(HttpRemoteException.class, () ->
                client.get()
                        .uri("/items/error")
                        .retrieve()
                        .body(String.class));

        assertEquals(500, ex.getHttpStatusCode());
        assertTrue(ex.getResponseBody().contains("Internal Error"));
    }

    @Test
    @DisplayName("测试 Spring 上下文自动装配 RestClientRegistry 并与 @EnableHttpInterfaces 无缝协同")
    void testAutoConfigurationIntegration() throws Exception {
        String baseUrl = mockWebServer.url("/").toString();

        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ateng.boot.http.clients.integration-service.base-url", baseUrl);
        context.setEnvironment(env);
        context.register(IntegrationTestConfig.class, AtengHttpAutoConfiguration.class);
        context.refresh();

        // 1. 验证 RestClientRegistry 已作为 Bean 成功注入容器
        assertTrue(context.containsBean("restClientRegistry"), "容器中应包含 restClientRegistry Bean");
        RestClientRegistry registry = context.getBean(RestClientRegistry.class);
        assertNotNull(registry);

        // 2. 验证声明式接口 IntegrationServiceClient 被成功代理并注入
        assertTrue(context.containsBean("integrationServiceClient"), "容器中应包含 integrationServiceClient Bean");
        IntegrationServiceClient serviceClient = context.getBean(IntegrationServiceClient.class);
        assertNotNull(serviceClient);

        // 3. 模拟请求并执行调用
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":999,\"name\":\"Integration User\"}"));

        Map<String, Object> user = serviceClient.getUser(999L);
        assertNotNull(user);
        assertEquals(999, user.get("id"));
        assertEquals("Integration User", user.get("name"));

        RecordedRequest recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        assertEquals("/users/999", recordedRequest.getPath());
        assertEquals("GET", recordedRequest.getMethod());
    }

    @Test
    @DisplayName("测试 defaultHeaders 占位符解析与出站请求头注入")
    void testDefaultHeadersWithPlaceholderResolution() throws Exception {
        String baseUrl = mockWebServer.url("/").toString();

        HttpProperties properties = new HttpProperties();
        HttpClientItemProperties item = new HttpClientItemProperties();
        item.setBaseUrl(baseUrl);
        item.setDefaultHeaders(Map.of(
                "X-Client-Version", "v1.2.0",
                "X-App-Env", "${app.environment}",
                "X-Dynamic-Token", "Bearer ${api.token:fallback-token}"
        ));
        properties.getClients().put("custom-header-client", item);

        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.environment", "production");
        env.setProperty("api.token", "secret-token-888");

        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        RestClientRegistry registry = new RestClientRegistry(builder, factory, properties, new HttpErrorHandler(), env);

        RestClient client = registry.getClient("custom-header-client");
        assertNotNull(client);

        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));
        client.get().uri("/api/ping").retrieve().toBodilessEntity();

        RecordedRequest recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertEquals("v1.2.0", recorded.getHeader("X-Client-Version"));
        assertEquals("production", recorded.getHeader("X-App-Env"));
        assertEquals("Bearer secret-token-888", recorded.getHeader("X-Dynamic-Token"));
    }

    @Test
    @DisplayName("测试客户端专属正向代理配置覆盖全局设置")
    void testClientScopedProxyConfiguration() {
        HttpProperties properties = new HttpProperties();
        HttpProxyProperties globalProxy = new HttpProxyProperties();
        globalProxy.setEnabled(true);
        globalProxy.setHost("global-proxy.local");
        globalProxy.setPort(8080);
        properties.setProxy(globalProxy);

        HttpClientItemProperties item = new HttpClientItemProperties();
        HttpProxyProperties clientProxy = new HttpProxyProperties();
        clientProxy.setEnabled(true);
        clientProxy.setHost("client-proxy.local");
        clientProxy.setPort(8888);
        item.setProxy(clientProxy);
        properties.getClients().put("scoped-proxy-client", item);

        MockEnvironment env = new MockEnvironment();
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        RestClientRegistry registry = new RestClientRegistry(builder, factory, properties, new HttpErrorHandler(), env);

        // 验证构建专属于 client-proxy 的独立 RestClient 实例
        RestClient client = registry.getClient("scoped-proxy-client");
        assertNotNull(client);
        assertTrue(registry.contains("scoped-proxy-client"));
    }

    @Test
    @DisplayName("测试客户端专属 mTLS 证书加载 (PKCS12 密钥库)")
    void testClientScopedMtlsKeyStoreLoading(@TempDir Path tempDir) throws Exception {
        // 创建合法的空 PKCS12 密钥库文件
        Path p12File = tempDir.resolve("wechat_cert.p12");
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, "cert_password".toCharArray());
        try (OutputStream out = Files.newOutputStream(p12File)) {
            keyStore.store(out, "cert_password".toCharArray());
        }

        HttpProperties properties = new HttpProperties();
        HttpClientItemProperties item = new HttpClientItemProperties();
        item.setKeyStore(p12File.toUri().toString());
        item.setKeyStorePassword("cert_password");
        item.setKeyStoreType("PKCS12");
        properties.getClients().put("wechat-pay", item);

        MockEnvironment env = new MockEnvironment();
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        RestClientRegistry registry = new RestClientRegistry(builder, factory, properties, new HttpErrorHandler(), env);

        RestClient client = registry.getClient("wechat-pay");
        assertNotNull(client);
        assertTrue(registry.contains("wechat-pay"));
    }

    @Test
    @DisplayName("测试客户端专属 mTLS 证书文件缺失时快速失败")
    void testClientScopedMtlsMissingFileThrows() {
        HttpProperties properties = new HttpProperties();
        HttpClientItemProperties item = new HttpClientItemProperties();
        item.setKeyStore("classpath:certs/nonexistent_cert.p12");
        item.setKeyStorePassword("secret");
        properties.getClients().put("bad-cert-client", item);

        MockEnvironment env = new MockEnvironment();
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        RestClientRegistry registry = new RestClientRegistry(builder, factory, properties, new HttpErrorHandler(), env);

        assertThrows(IllegalArgumentException.class, () -> registry.getClient("bad-cert-client"));
    }

    @Test
    @DisplayName("测试客户端专属 proxy/ssl 初始化独立工厂时正常回调 HttpClient5Customizer")
    void testClientScopedPhysicalFactoryInvokesCustomizer() {
        HttpProperties properties = new HttpProperties();
        HttpClientItemProperties item = new HttpClientItemProperties();
        HttpProxyProperties proxy = new HttpProxyProperties();
        proxy.setHost("127.0.0.1");
        proxy.setPort(8888);
        item.setProxy(proxy);
        properties.getClients().put("custom-proxy-client", item);

        java.util.concurrent.atomic.AtomicBoolean customizerInvoked = new java.util.concurrent.atomic.AtomicBoolean(false);
        io.github.atengk.boot.http.customizer.HttpClient5Customizer customizer = (clientBuilder, connManagerBuilder) -> {
            customizerInvoked.set(true);
        };

        MockEnvironment env = new MockEnvironment();
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        RestClientRegistry registry = new RestClientRegistry(
                builder,
                factory,
                properties,
                new HttpErrorHandler(),
                env,
                null,
                List.of(customizer)
        );

        RestClient client = registry.getClient("custom-proxy-client");
        assertNotNull(client);
        assertTrue(customizerInvoked.get(), "专属客户端物理工厂创建时应正确触发 HttpClient5Customizer 回调");
    }

    @Configuration
    @EnableHttpInterfaces(clients = IntegrationServiceClient.class)
    static class IntegrationTestConfig {
    }

    @HttpClient(name = "integration-service")
    public interface IntegrationServiceClient {
        @GetExchange("/users/{id}")
        Map<String, Object> getUser(@PathVariable("id") Long id);
    }
}
