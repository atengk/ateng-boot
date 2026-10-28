package io.github.atengk.boot.http.interceptor;

import io.github.atengk.boot.http.properties.HttpClientItemProperties;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.properties.HttpRetryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 出站 HTTP 弹性指数退避重试拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class HttpRetryInterceptorTest {

    private HttpProperties properties;
    private HttpRetryInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new HttpProperties();
        // 开启全局重试，设置极短退避间隔 (10ms) 以保障单测极速秒级运行
        HttpRetryProperties globalRetry = properties.getRetry();
        globalRetry.setEnabled(true);
        globalRetry.setMaxAttempts(3);
        globalRetry.setDelay(Duration.ofMillis(10));
        globalRetry.setMultiplier(1.5);
        globalRetry.setMaxDelay(Duration.ofMillis(50));
        globalRetry.setJitter(false);

        // 配置专属命名客户端：定制最大重试 2 次
        HttpClientItemProperties orderClient = new HttpClientItemProperties();
        orderClient.setBaseUrl("https://orders.internal.com");
        HttpRetryProperties orderRetry = new HttpRetryProperties();
        orderRetry.setEnabled(true);
        orderRetry.setMaxAttempts(2);
        orderRetry.setDelay(Duration.ofMillis(10));
        orderClient.setRetry(orderRetry);
        properties.getClients().put("order-service", orderClient);

        interceptor = new HttpRetryInterceptor(properties);
    }

    @Test
    @DisplayName("状态码抖动重试成功：前 2 次返回 503，第 3 次返回 200，断言自动恢复且中间失败响应被显式关闭归还连接池")
    void shouldRetryOn503AndSucceedEventually() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.example.com/items"));
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicBoolean attempt1Closed = new AtomicBoolean(false);
        AtomicBoolean attempt2Closed = new AtomicBoolean(false);

        ClientHttpRequestExecution execution = (req, body) -> {
            int attempt = callCount.incrementAndGet();
            if (attempt == 1) {
                return new MockClientHttpResponse(new byte[0], HttpStatus.SERVICE_UNAVAILABLE) {
                    @Override
                    public void close() {
                        attempt1Closed.set(true);
                        super.close();
                    }
                };
            }
            if (attempt == 2) {
                return new MockClientHttpResponse(new byte[0], HttpStatus.SERVICE_UNAVAILABLE) {
                    @Override
                    public void close() {
                        attempt2Closed.set(true);
                        super.close();
                    }
                };
            }
            return new MockClientHttpResponse("{\"success\":true}".getBytes(), HttpStatus.OK);
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, callCount.get(), "应触发 2 次重试，总共执行 3 次调用");
        assertTrue(attempt1Closed.get(), "第 1 次失败轮次的物理响应流必须被显式关闭归还连接池");
        assertTrue(attempt2Closed.get(), "第 2 次失败轮次的物理响应流必须被显式关闭归还连接池");
    }

    @Test
    @DisplayName("状态码重试耗尽：连续返回 503，耗尽 3 次后返回最后一次失败响应供下游转译")
    void shouldExhaustRetriesOnContinuous503() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.example.com/items"));
        AtomicInteger callCount = new AtomicInteger(0);

        ClientHttpRequestExecution execution = (req, body) -> {
            callCount.incrementAndGet();
            return new MockClientHttpResponse(new byte[0], HttpStatus.SERVICE_UNAVAILABLE);
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(3, callCount.get(), "达到上限 3 次后应停止重试并返回最终响应");
    }

    @Test
    @DisplayName("网络 I/O 抖动重试成功：第 1 次遭遇 SocketTimeoutException，第 2 次成功返回 200")
    void shouldRetryOnSocketTimeoutAndSucceed() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.example.com/flaky"));
        AtomicInteger callCount = new AtomicInteger(0);

        ClientHttpRequestExecution execution = (req, body) -> {
            int attempt = callCount.incrementAndGet();
            if (attempt == 1) {
                throw new SocketTimeoutException("Read timed out");
            }
            return new MockClientHttpResponse("{\"data\":1}".getBytes(), HttpStatus.OK);
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, callCount.get(), "超时后应自动重试并在第 2 次成功");
    }

    @Test
    @DisplayName("网络 I/O 异常耗尽：连续抛出 ConnectException，重试耗尽后向外抛出原始异常因果链")
    void shouldExhaustRetriesAndThrowRootCauseOnContinuousNetworkFailure() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://down.example.com/api"));
        AtomicInteger callCount = new AtomicInteger(0);

        ClientHttpRequestExecution execution = (req, body) -> {
            callCount.incrementAndGet();
            throw new ConnectException("Connection refused");
        };

        ConnectException ex = assertThrows(ConnectException.class, () -> interceptor.intercept(request, new byte[0], execution));
        assertEquals("Connection refused", ex.getMessage());
        assertEquals(3, callCount.get(), "网络异常重试应达到最大 3 次上限");
    }

    @Test
    @DisplayName("幂等安全铁律：POST 等非幂等动词默认绝不重试，即使遭遇 503 也快速失败")
    void shouldNeverRetryNonIdempotentMethodsByDefault() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://api.example.com/orders"));
        AtomicInteger callCount = new AtomicInteger(0);

        ClientHttpRequestExecution execution = (req, body) -> {
            callCount.incrementAndGet();
            return new MockClientHttpResponse(new byte[0], HttpStatus.SERVICE_UNAVAILABLE);
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(1, callCount.get(), "非幂等 POST 请求必须仅调用 1 次，坚决禁止重试");
    }

    @Test
    @DisplayName("4xx 业务错误判定：404/400 等客户端业务错误绝不触发重试")
    void shouldNotRetryOn4xxClientErrors() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.example.com/not-found"));
        AtomicInteger callCount = new AtomicInteger(0);

        ClientHttpRequestExecution execution = (req, body) -> {
            callCount.incrementAndGet();
            return new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND);
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(1, callCount.get(), "404 错误不应触发重试");
    }

    @Test
    @DisplayName("命名客户端专属策略：匹配 order-service 时严格遵循其 maxAttempts=2 的专属限制")
    void shouldApplyClientSpecificRetryProperties() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://orders.internal.com/query"));
        AtomicInteger callCount = new AtomicInteger(0);

        ClientHttpRequestExecution execution = (req, body) -> {
            callCount.incrementAndGet();
            return new MockClientHttpResponse(new byte[0], HttpStatus.BAD_GATEWAY);
        };

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals(2, callCount.get(), "应采用命名客户端配置的 maxAttempts=2");
    }
}
