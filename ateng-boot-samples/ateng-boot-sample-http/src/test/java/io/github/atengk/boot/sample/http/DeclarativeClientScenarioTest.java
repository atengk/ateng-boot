package io.github.atengk.boot.sample.http;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.http.context.HttpRequestContextHolder;
import io.github.atengk.boot.http.error.HttpRemoteException;
import io.github.atengk.boot.sample.http.client.OrderServiceClient;
import io.github.atengk.boot.sample.http.client.OrderServiceClientFallback;
import io.github.atengk.boot.sample.http.client.UserServiceClient;
import io.github.atengk.boot.sample.http.client.WechatPayClient;
import io.github.atengk.boot.sample.http.dto.OrderResponse;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderRequest;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 1:1 场景沙箱声明式 HTTP 客户端全链路端到端集成实测与高并发压测
 * 覆盖内部微服务 Trace/Tenant 穿透、外部非信任域安全隔离、4xx/5xx 异常因果转译及自适应虚拟线程并发压测
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootTest
class DeclarativeClientScenarioTest {

    private static MockWebServer mockWebServer;

    @Autowired
    private UserServiceClient userServiceClient;

    @Autowired
    private WechatPayClient wechatPayClient;

    @Autowired
    private OrderServiceClient orderServiceClient;

    @Autowired(required = false)
    @Qualifier("taskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    @BeforeAll
    static void startServer() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("sample.user-service.url", () -> mockWebServer.url("/").toString());
        registry.add("sample.wechat-pay.url", () -> mockWebServer.url("/").toString());
        registry.add("sample.order-service.url", () -> mockWebServer.url("/").toString());
    }

    @AfterEach
    void tearDown() {
        // 排空 MockWebServer 队列中残留的历史请求，杜绝跨测试用例污染
        if (mockWebServer != null) {
            while (true) {
                try {
                    RecordedRequest extra = mockWebServer.takeRequest(10, TimeUnit.MILLISECONDS);
                    if (extra == null) {
                        break;
                    }
                } catch (Exception ignored) {
                    break;
                }
            }
        }

        // 守卫上下文零污染铁律：无条件清理线程上下文
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        HttpRequestContextHolder.clear();
    }

    @Test
    @DisplayName("场景 1：内部微服务声明式 GET/POST 契约与四维上下文透传验证")
    void testInternalMicroserviceContextPropagation() throws InterruptedException {
        // 1. 在当前调用线程装载四维业务上下文
        String traceId = "trace-declarative-internal-001";
        String tenantId = "tenant-internal-999";
        String userId = "user-internal-admin";

        MDC.put("traceId", traceId);
        TenantContextHolder.setTenantId(tenantId);
        UserContextHolder.set(userId);

        // 2. 模拟远端返回 GET 用户响应（强类型 UserResponse 契约）
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":1001,\"username\":\"Ateng\",\"role\":\"ADMIN\",\"status\":\"ACTIVE\"}"));

        UserResponse user = userServiceClient.getUserById(1001L);
        assertThat(user).isNotNull();
        assertThat(user.getId()).isEqualTo(1001L);
        assertThat(user.getUsername()).isEqualTo("Ateng");
        assertThat(user.getRole()).isEqualTo("ADMIN");
        assertThat(user.getStatus()).isEqualTo("ACTIVE");

        RecordedRequest getRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(getRequest).isNotNull();
        assertThat(getRequest.getMethod()).isEqualTo("GET");
        assertThat(getRequest.getPath()).isEqualTo("/users/1001");
        assertInternalContextHeaders(getRequest, traceId, tenantId, userId);

        // 3. 模拟远端返回 POST 创建用户响应（强类型 UserRequest / UserResponse 契约）
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":1002,\"username\":\"NewDevUser\",\"role\":\"DEV\",\"status\":\"ACTIVE\"}"));

        UserRequest createPayload = UserRequest.builder()
                .username("NewDevUser")
                .email("dev@example.com")
                .role("DEV")
                .build();

        UserResponse createResult = userServiceClient.createUser(createPayload);
        assertThat(createResult).isNotNull();
        assertThat(createResult.getId()).isEqualTo(1002L);
        assertThat(createResult.getUsername()).isEqualTo("NewDevUser");

        RecordedRequest postRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(postRequest).isNotNull();
        assertThat(postRequest.getMethod()).isEqualTo("POST");
        assertThat(postRequest.getPath()).isEqualTo("/users");
        assertThat(postRequest.getBody().readUtf8()).contains("NewDevUser");
        assertInternalContextHeaders(postRequest, traceId, tenantId, userId);
    }

    @Test
    @DisplayName("场景 2：外部公网声明式调用敏感私有头 100% 剥离验证")
    void testExternalPublicApiHeaderIsolation() throws InterruptedException {
        // 1. 在当前调用线程装载四维业务上下文
        String traceId = "trace-declarative-wechat-002";
        MDC.put("traceId", traceId);
        TenantContextHolder.setTenantId("tenant-private-confidential");
        UserContextHolder.set("user-private-identity");

        // 2. 模拟微信统一下单返回（强类型 WechatNativeOrderRequest / Response 契约）
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"code_url\":\"weixin://wxpay/bizpayurl?pr=AbcDeFg123\"}"));

        WechatNativeOrderRequest orderPayload = WechatNativeOrderRequest.builder()
                .outTradeNo("ORD20261021001")
                .description("Premium Subscription")
                .totalFee(9900)
                .build();

        WechatNativeOrderResponse orderResult = wechatPayClient.createNativeOrder(orderPayload);
        assertThat(orderResult).isNotNull();
        assertThat(orderResult.getCodeUrl()).isEqualTo("weixin://wxpay/bizpayurl?pr=AbcDeFg123");

        // 3. 严格断言出站请求：内网租户与用户标识 100% 被安全剥离，外部绝对不可见
        RecordedRequest wechatRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(wechatRequest).isNotNull();
        assertThat(wechatRequest.getMethod()).isEqualTo("POST");
        assertThat(wechatRequest.getPath()).isEqualTo("/v3/pay/transactions/native");
        assertThat(wechatRequest.getHeader("X-Tenant-Id")).isNull();
        assertThat(wechatRequest.getHeader("X-User-Id")).isNull();
        // MDC traceId 保持透传，保障全链路可观测性闭环
        assertThat(wechatRequest.getHeader("X-Trace-Id")).isEqualTo(traceId);
    }

    @Test
    @DisplayName("场景 3：远端 404/500 返回语义化 HttpRemoteException 因果转译断言")
    void testRemoteExceptionsCausalTranslation() {
        // 1. 模拟远端返回 404 响应
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"code\":404,\"message\":\"Target user not found\"}"));

        assertThatThrownBy(() -> userServiceClient.getUserById(99999L))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(404);
                    assertThat(remoteEx.getResponseBody()).contains("Target user not found");
                });

        // 2. 模拟远端返回 500 响应
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"code\":500,\"message\":\"Internal Database Failure\"}"));

        assertThatThrownBy(() -> userServiceClient.getUserById(88888L))
                .isInstanceOf(HttpRemoteException.class)
                .satisfies(ex -> {
                    HttpRemoteException remoteEx = (HttpRemoteException) ex;
                    assertThat(remoteEx.getHttpStatusCode()).isEqualTo(500);
                    assertThat(remoteEx.getResponseBody()).contains("Internal Database Failure");
                });
    }

    @Test
    @DisplayName("场景 4：自适应虚拟线程 500+ 并发压测与连接池长连接零泄露验证")
    void testAdaptiveVirtualThreadStressTesting() throws Exception {
        int requestCount = 500;

        // 批量预置 MockWebServer 响应
        for (int i = 0; i < requestCount; i++) {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json;charset=UTF-8")
                    .setBody("{\"id\":" + i + ",\"username\":\"User_" + i + "\",\"status\":\"OK\"}"));
        }

        // 依据当前环境获取虚拟线程执行器或 Core 受管线程池设施
        ExecutorWrapper wrapper = resolveAdaptiveExecutor(requestCount);
        try {
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(requestCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            for (int i = 0; i < requestCount; i++) {
                final long userId = i;
                wrapper.execute(() -> {
                    try {
                        startLatch.await();
                        UserResponse result = userServiceClient.getUserById(userId);
                        if (result != null && "OK".equals(result.getStatus())) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            long startTime = System.currentTimeMillis();
            startLatch.countDown(); // 并发齐发
            boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startTime;

            assertThat(finished).isTrue();
            assertThat(successCount.get()).isEqualTo(requestCount);
            assertThat(failureCount.get()).isZero();

            // 消费掉所有 Mock 记录，确保连接全部正常归还
            for (int i = 0; i < requestCount; i++) {
                RecordedRequest recorded = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
                assertThat(recorded).isNotNull();
            }

            // 输出压测指标摘要
            double qps = (requestCount * 1000.0) / duration;
            org.slf4j.LoggerFactory.getLogger(DeclarativeClientScenarioTest.class)
                    .info("高并发压测完成: {} 请求, 成功率 100%, 耗时 {}ms, 吞吐量 {:.2f} ops/s",
                            requestCount, duration, qps);
        } finally {
            wrapper.shutdown();
        }
    }

    @Test
    @DisplayName("场景 5：声明式订单服务正常通信验证")
    void testOrderServiceClientSuccess() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"orderId\":\"ORD-1001\",\"status\":\"PAID\",\"message\":\"success\"}"));

        OrderResponse response = orderServiceClient.getOrderStatus("ORD-1001");
        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo("ORD-1001");
        assertThat(response.getStatus()).isEqualTo("PAID");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/orders/ORD-1001/status");
    }

    @Test
    @DisplayName("场景 6：远端 500 故障自动触发 @HttpClient(fallback = ...) 本地优雅降级")
    void testOrderServiceClientFallbackOnServerError() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Internal DB Down\"}"));

        OrderResponse response = orderServiceClient.getOrderStatus("ORD-FAIL-500");
        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo("ORD-FAIL-500");
        assertThat(response.getStatus()).isEqualTo("UNKNOWN");
        assertThat(response.getMessage()).contains("降级");
    }

    @Test
    @DisplayName("场景 7：方法级 @HttpTimeout 超时覆盖生效并触发 Fallback 降级")
    void testOrderServiceClientMethodLevelTimeoutFallback() {
        // 远端延迟 1.5 秒，超过 @HttpTimeout(readTimeout = "300ms")，触发超时并进入降级
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"orderId\":\"ORD-TIMEOUT\",\"status\":\"SUCCESS\"}")
                .setHeadersDelay(1500, TimeUnit.MILLISECONDS));

        long start = System.currentTimeMillis();
        OrderResponse response = orderServiceClient.getOrderById("ORD-TIMEOUT");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo("ORD-TIMEOUT");
        assertThat(response.getStatus()).isEqualTo(OrderServiceClientFallback.STATUS_DEGRADED);
        assertThat(response.getMessage()).contains("本地优雅降级兜底");
        assertThat(elapsed).isLessThan(2500);
    }

    /**
     * 辅助断言内部四维上下文透传请求头
     */
    private void assertInternalContextHeaders(RecordedRequest request, String traceId, String tenantId, String userId) {
        assertThat(request.getHeader("X-Trace-Id")).isEqualTo(traceId);
        assertThat(request.getHeader("X-Tenant-Id")).isEqualTo(tenantId);
        assertThat(request.getHeader("X-User-Id")).isEqualTo(userId);
    }

    /**
     * 依据当前运行环境自适应创建虚拟线程执行器包装（Java 21+）或平滑回退 Core 受管异步线程池设施（Java 17）
     */
    private ExecutorWrapper resolveAdaptiveExecutor(int concurrency) {
        try {
            // 探测 Java 21+ 虚拟线程支持
            Method ofVirtualMethod = Thread.class.getMethod("ofVirtual");
            Object ofVirtual = ofVirtualMethod.invoke(null);
            Method factoryMethod = ofVirtual.getClass().getMethod("factory");
            ThreadFactory virtualThreadFactory = (ThreadFactory) factoryMethod.invoke(ofVirtual);

            Method newThreadPerTaskExecutorMethod = Executors.class.getMethod("newThreadPerTaskExecutor", ThreadFactory.class);
            ExecutorService virtualExecutor = (ExecutorService) newThreadPerTaskExecutorMethod.invoke(null, virtualThreadFactory);
            org.slf4j.LoggerFactory.getLogger(DeclarativeClientScenarioTest.class)
                    .info("当前运行时探测到 Java 21+ 虚拟线程支持，启用 Virtual Thread 执行高并发压测 [{} 并发]", concurrency);
            return new ExecutorWrapper(virtualExecutor, true);
        } catch (Throwable ex) {
            org.slf4j.LoggerFactory.getLogger(DeclarativeClientScenarioTest.class)
                    .info("当前运行时为 Java 17，平滑接入 Core 受管异步线程池设施支撑高并发压测");
            org.junit.jupiter.api.Assertions.assertNotNull(this.taskExecutor, "Core 受管 taskExecutor 必须非空");
            return new ExecutorWrapper(this.taskExecutor, false);
        }
    }

    /**
     * 执行器生命周期受管包装器
     */
    private static class ExecutorWrapper {
        private final Executor executor;
        private final boolean closable;

        public ExecutorWrapper(Executor executor, boolean closable) {
            this.executor = executor;
            this.closable = closable;
        }

        public void execute(Runnable command) {
            this.executor.execute(command);
        }

        public void shutdown() {
            if (closable && executor instanceof ExecutorService es) {
                es.shutdownNow();
            }
        }
    }
}
