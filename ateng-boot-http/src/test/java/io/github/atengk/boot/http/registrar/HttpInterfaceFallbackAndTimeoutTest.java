package io.github.atengk.boot.http.registrar;

import io.github.atengk.boot.http.annotation.EnableHttpInterfaces;
import io.github.atengk.boot.http.context.HttpRequestContextHolder;
import io.github.atengk.boot.http.error.HttpErrorHandler;
import io.github.atengk.boot.http.error.HttpRemoteException;
import io.github.atengk.boot.http.registrar.errorfixture.InvalidFallbackClient;
import io.github.atengk.boot.http.registrar.fixture.NoFallbackClient;
import io.github.atengk.boot.http.registrar.fixture.TimeoutAndFallbackClient;
import io.github.atengk.boot.http.registrar.fixture.TimeoutAndFallbackClientFallback;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 声明式客户端 @HttpTimeout 超时优先级与 @HttpClient.fallback 本地优雅降级集成测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class HttpInterfaceFallbackAndTimeoutTest {

    static final AtomicReference<Duration> CAPTURED_READ_TIMEOUT = new AtomicReference<>();
    static final AtomicReference<Duration> CAPTURED_CONNECT_TIMEOUT = new AtomicReference<>();

    private MockWebServer mockWebServer;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        HttpRequestContextHolder.clear();
        CAPTURED_READ_TIMEOUT.set(null);
        CAPTURED_CONNECT_TIMEOUT.set(null);
    }

    @AfterEach
    void tearDown() throws IOException {
        HttpRequestContextHolder.clear();
        CAPTURED_READ_TIMEOUT.set(null);
        CAPTURED_CONNECT_TIMEOUT.set(null);
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
    @DisplayName("测试 @HttpTimeout 超时优先级（方法级 > 接口级）与上下文现场对称清理")
    void testHttpTimeoutPrecedenceAndContextRestoration() {
        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("test.timeout-fallback.url", mockWebServer.url("/api").toString());
        env.setProperty("test.custom.timeout", "6s");
        context.setEnvironment(env);
        context.register(TestAppConfig.class);
        context.refresh();

        TimeoutAndFallbackClient client = context.getBean(TimeoutAndFallbackClient.class);
        assertNotNull(client);

        // 1. 验证接口级 @HttpTimeout 继承生效 (readTimeout=3s, connectTimeout=2s)
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok-default"));
        String res1 = client.defaultTimeout();
        assertEquals("ok-default", res1);
        assertEquals(Duration.ofSeconds(3), CAPTURED_READ_TIMEOUT.get(), "接口级读取超时应为 3s");
        assertEquals(Duration.ofSeconds(2), CAPTURED_CONNECT_TIMEOUT.get(), "接口级建联超时应为 2s");
        assertNull(HttpRequestContextHolder.getReadTimeout(), "调用结束后上下文应彻底清理");
        assertNull(HttpRequestContextHolder.getConnectTimeout(), "调用结束后上下文应彻底清理");

        // 2. 验证方法级 @HttpTimeout 覆盖接口级 (readTimeout=500ms 覆盖，connectTimeout=2s 继承)
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok-method"));
        String res2 = client.methodTimeout();
        assertEquals("ok-method", res2);
        assertEquals(Duration.ofMillis(500), CAPTURED_READ_TIMEOUT.get(), "方法级读取超时应覆盖为 500ms");
        assertEquals(Duration.ofSeconds(2), CAPTURED_CONNECT_TIMEOUT.get(), "未声明建联超时的方法应继承接口级 2s");
        assertNull(HttpRequestContextHolder.getReadTimeout());
        assertNull(HttpRequestContextHolder.getConnectTimeout());

        // 3. 验证纯数字毫秒解析 (readTimeout=1000 -> 1000ms, connectTimeout=500 -> 500ms)
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok-digits"));
        String res3 = client.pureDigitsTimeout();
        assertEquals("ok-digits", res3);
        assertEquals(Duration.ofMillis(1000), CAPTURED_READ_TIMEOUT.get());
        assertEquals(Duration.ofMillis(500), CAPTURED_CONNECT_TIMEOUT.get());
        assertNull(HttpRequestContextHolder.getReadTimeout());
        assertNull(HttpRequestContextHolder.getConnectTimeout());

        // 4. 验证环境变量与占位符解析 (test.custom.timeout=6s)
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok-placeholder"));
        String res4 = client.placeholderTimeout();
        assertEquals("ok-placeholder", res4);
        assertEquals(Duration.ofSeconds(6), CAPTURED_READ_TIMEOUT.get(), "环境变量占位符 6s 应正确解析生效");
        assertNull(HttpRequestContextHolder.getReadTimeout());
        assertNull(HttpRequestContextHolder.getConnectTimeout());
    }

    @Test
    @DisplayName("测试异常时仍然恪守上下文对称清理与已有现场恢复")
    void testContextRestorationWhenExceptionOrPriorContext() {
        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("test.timeout-fallback.url", mockWebServer.url("/api").toString());
        context.setEnvironment(env);
        context.register(TestAppConfig.class);
        context.refresh();

        TimeoutAndFallbackClient client = context.getBean(TimeoutAndFallbackClient.class);

        // 预设外层调用者上下文
        Duration outerReadTimeout = Duration.ofSeconds(99);
        Duration outerConnectTimeout = Duration.ofSeconds(88);
        HttpRequestContextHolder.setReadTimeout(outerReadTimeout);
        HttpRequestContextHolder.setConnectTimeout(outerConnectTimeout);

        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        client.defaultTimeout();

        // 验证执行后外层上下文被精准无损恢复
        assertEquals(outerReadTimeout, HttpRequestContextHolder.getReadTimeout());
        assertEquals(outerConnectTimeout, HttpRequestContextHolder.getConnectTimeout());

        HttpRequestContextHolder.clear();
    }

    @Test
    @DisplayName("测试远端服务返回 500 触发本地 fallback 优雅降级")
    void testFallbackOnHttpError500() {
        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("test.timeout-fallback.url", mockWebServer.url("/api").toString());
        context.setEnvironment(env);
        context.register(TestAppConfig.class);
        context.refresh();

        TimeoutAndFallbackClient client = context.getBean(TimeoutAndFallbackClient.class);

        // 模拟远端返回 500 内部服务错误
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Internal Server Error\"}"));

        String result = client.simulateError(500);
        assertEquals("fallback:handledError:500", result, "远端 500 错误应被优雅降级拦截处理");
        assertNull(HttpRequestContextHolder.getReadTimeout(), "降级完成后上下文应彻底清理");
    }

    @Test
    @DisplayName("测试远端服务返回 404 触发本地 fallback 优雅降级")
    void testFallbackOnHttpError404() {
        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("test.timeout-fallback.url", mockWebServer.url("/api").toString());
        context.setEnvironment(env);
        context.register(TestAppConfig.class);
        context.refresh();

        TimeoutAndFallbackClient client = context.getBean(TimeoutAndFallbackClient.class);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Not Found\"}"));

        String result = client.simulateError(404);
        assertEquals("fallback:handledError:404", result, "远端 404 错误应被优雅降级拦截处理");
    }

    @Test
    @DisplayName("测试网络不可达或连接故障时触发本地 fallback 优雅降级")
    void testFallbackOnNetworkFailure() throws IOException {
        int closedPort = mockWebServer.getPort();
        mockWebServer.shutdown();
        mockWebServer = null;

        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        // 目标为已关闭的端口，立即产生 ConnectException (Connection refused)
        env.setProperty("test.timeout-fallback.url", "http://127.0.0.1:" + closedPort);
        context.setEnvironment(env);
        context.register(TestAppConfig.class);
        context.refresh();

        TimeoutAndFallbackClient client = context.getBean(TimeoutAndFallbackClient.class);

        String result = client.defaultTimeout();
        assertEquals("fallback:defaultTimeout", result, "网络建联故障应平滑触发 fallback 降级");
    }

    @Test
    @DisplayName("测试未配置 fallback 时远端错误原样向外抛出异常")
    void testNoFallbackThrowsOriginalException() {
        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("test.no-fallback.url", mockWebServer.url("/api").toString());
        context.setEnvironment(env);
        context.register(NoFallbackConfig.class);
        context.refresh();

        NoFallbackClient client = context.getBean(NoFallbackClient.class);

        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Server Error"));

        HttpRemoteException exception = assertThrows(HttpRemoteException.class, client::test);
        assertEquals(500, exception.getHttpStatusCode());
        assertTrue(exception.getResponseBody().contains("Server Error"));
    }

    @Test
    @DisplayName("测试 fallback 类未实现目标接口触发 Fail-Fast 启动熔断")
    void testInvalidFallbackClassFailsFast() {
        HttpInterfaceFactoryBean<InvalidFallbackClient> factoryBean = new HttpInterfaceFactoryBean<>();
        factoryBean.setInterfaceType(InvalidFallbackClient.class);
        factoryBean.setClientName("invalid-client");
        factoryBean.setFallback(String.class);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                factoryBean::afterPropertiesSet,
                "未实现接口的 fallback 类型必须在初始化阶段 Fail-Fast 抛出异常");
        assertTrue(exception.getMessage().contains("must implement interface"));
    }

    @Test
    @DisplayName("测试声明了 fallback 但容器中未注册对应 Bean 时产生告警并抛出原始异常")
    void testMissingFallbackBeanInContextRethrows() {
        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("test.timeout-fallback.url", mockWebServer.url("/api").toString());
        context.setEnvironment(env);
        context.register(ConfigWithoutFallbackBean.class);
        context.refresh();

        TimeoutAndFallbackClient client = context.getBean(TimeoutAndFallbackClient.class);

        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Service Internal Error"));

        HttpRemoteException exception = assertThrows(HttpRemoteException.class, () -> client.simulateError(500));
        assertEquals(500, exception.getHttpStatusCode());
    }

    @Test
    @DisplayName("测试代理对象 equals/hashCode/toString 契约")
    void testProxyObjectMethods() {
        context = new AnnotationConfigApplicationContext();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("test.timeout-fallback.url", mockWebServer.url("/api").toString());
        context.setEnvironment(env);
        context.register(TestAppConfig.class);
        context.refresh();

        TimeoutAndFallbackClient client = context.getBean(TimeoutAndFallbackClient.class);
        assertNotNull(client.toString());
        assertTrue(client.toString().contains("HttpInterfaceProxy"));
        assertTrue(client.toString().contains("timeout-fallback-service"));
        assertEquals(client, client);
        assertNotEquals(client, new Object());
        assertNotEquals(0, client.hashCode());
    }

    @Configuration
    @EnableHttpInterfaces(clients = TimeoutAndFallbackClient.class)
    static class TestAppConfig {

        @Bean
        public TimeoutAndFallbackClientFallback timeoutAndFallbackClientFallback() {
            return new TimeoutAndFallbackClientFallback();
        }

        @Bean
        public RestClient.Builder restClientBuilder() {
            return RestClient.builder()
                    .defaultStatusHandler(new HttpErrorHandler())
                    .requestInterceptor((request, body, execution) -> {
                        CAPTURED_READ_TIMEOUT.set(HttpRequestContextHolder.getReadTimeout());
                        CAPTURED_CONNECT_TIMEOUT.set(HttpRequestContextHolder.getConnectTimeout());
                        return execution.execute(request, body);
                    });
        }
    }

    @Configuration
    @EnableHttpInterfaces(clients = NoFallbackClient.class)
    static class NoFallbackConfig {

        @Bean
        public RestClient.Builder restClientBuilder() {
            return RestClient.builder().defaultStatusHandler(new HttpErrorHandler());
        }
    }

    @Configuration
    @EnableHttpInterfaces(clients = TimeoutAndFallbackClient.class)
    static class ConfigWithoutFallbackBean {

        @Bean
        public RestClient.Builder restClientBuilder() {
            return RestClient.builder().defaultStatusHandler(new HttpErrorHandler());
        }
    }

}
