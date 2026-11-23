package io.github.atengk.boot.tcp.route;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.annotation.TcpDeviceId;
import io.github.atengk.boot.tcp.annotation.TcpHeader;
import io.github.atengk.boot.tcp.annotation.TcpListener;
import io.github.atengk.boot.tcp.annotation.TcpMapping;
import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.enums.FramingMode;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.spi.TcpMessageHandler;
import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 注解驱动路由分发与线程池异步隔离端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 注解驱动路由分发与异步隔离集成测试")
class TcpAnnotationRouteIntegrationTest {

    private ThreadPoolTaskExecutor businessExecutor;
    private GenericApplicationContext springContext;

    private TcpServer server;
    private TcpSessionManager sessionManager;
    private TcpRequestCorrelationManager serverCorrelationManager;
    private TcpDispatcher dispatcher;

    private TcpClient client;
    private TcpRequestCorrelationManager clientCorrelationManager;
    private TcpClientTemplate clientTemplate;

    private SampleOrderListener orderListener;
    private SampleDeviceStatusHandler statusHandler;

    @BeforeEach
    void setUp() throws Exception {
        // 1. 初始化业务异步隔离线程池
        businessExecutor = new ThreadPoolTaskExecutor();
        businessExecutor.setCorePoolSize(4);
        businessExecutor.setMaxPoolSize(8);
        businessExecutor.setQueueCapacity(100);
        businessExecutor.setThreadNamePrefix("tcp-biz-worker-");
        businessExecutor.initialize();

        // 2. 初始化 Spring 上下文与组件
        springContext = new GenericApplicationContext();
        orderListener = new SampleOrderListener();
        statusHandler = new SampleDeviceStatusHandler();
        springContext.registerBean("sampleOrderListener", SampleOrderListener.class, () -> orderListener);
        springContext.registerBean("sampleDeviceStatusHandler", SampleDeviceStatusHandler.class, () -> statusHandler);
        springContext.refresh();

        // 3. 构建分发器并手动触发初始化
        TcpRouteKeyExtractor extractor = new JsonRouteKeyExtractor();
        dispatcher = new TcpDispatcher(springContext, extractor, businessExecutor);
        dispatcher.afterPropertiesSet();

        // 4. 启动 TCP 服务端
        TcpServerProperties serverProps = new TcpServerProperties();
        serverProps.setPort(0);
        serverProps.getFraming().setMode(FramingMode.DELIMITER);
        serverProps.getFraming().setDelimiter("\n");

        sessionManager = new TcpSessionManager();
        serverCorrelationManager = new TcpRequestCorrelationManager();

        server = new TcpServer(
                serverProps,
                sessionManager,
                serverCorrelationManager,
                dispatcher,
                Collections.emptyList(),
                Collections.emptyList(),
                null
        );
        server.start();

        int port = server.getActualPort();
        assertThat(port).isGreaterThan(0);

        // 5. 启动 TCP 客户端
        TcpClientProperties clientProps = new TcpClientProperties();
        clientProps.setEnabled(true);
        clientProps.setHost("127.0.0.1");
        clientProps.setPort(port);
        clientProps.getFraming().setMode(FramingMode.DELIMITER);
        clientProps.getFraming().setDelimiter("\n");

        clientCorrelationManager = new TcpRequestCorrelationManager();
        client = new TcpClient(
                clientProps,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                clientCorrelationManager
        );
        client.start();

        clientTemplate = new TcpClientTemplate(client, clientProps, clientCorrelationManager);

        // 等待连接就绪
        long deadline = System.currentTimeMillis() + 5000;
        while ((!client.isConnected() || sessionManager.getActiveCount() == 0) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(client.isConnected()).isTrue();
        assertThat(sessionManager.getActiveCount()).isEqualTo(1);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.stop();
            } catch (Exception ignored) {
            }
        }
        if (clientCorrelationManager != null) {
            clientCorrelationManager.close();
        }
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        if (serverCorrelationManager != null) {
            serverCorrelationManager.close();
        }
        if (springContext != null) {
            springContext.close();
        }
        if (businessExecutor != null) {
            businessExecutor.shutdown();
        }
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("验证 @TcpListener 与 @TcpMapping 强类型参数自动映射、异步线程池隔离与双向 RPC 响应闭环")
    void testAnnotationRoutingAndAsyncRpc() {
        String reqId = IdUtil.fastSimpleUUID();
        String json = "{\"command\":\"ORDER_CREATE\",\"requestId\":\"" + reqId + "\",\"orderId\":\"ORD-8888\",\"amount\":9999}\n";
        TcpPacket reqPacket = TcpPacket.of("ORDER_CREATE", reqId, TcpPayload.of(json));

        // 客户端发起 RPC 请求，期望接收 OrderCreateResponse
        OrderCreateResponse response = clientTemplate.request(reqPacket, OrderCreateResponse.class, Duration.ofSeconds(3));

        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo("ORD-8888");
        assertThat(response.getStatus()).isEqualTo("CREATED");
        // 验证业务在受管异步隔离线程池中执行
        assertThat(response.getWorkerThread()).startsWith("tcp-biz-worker-");
        // 验证 MDC traceId 穿透成功传递
        assertThat(response.getTraceId()).isNotBlank();
    }

    @Test
    @DisplayName("验证 @TcpDeviceId 与 @TcpHeader 参数注入及字符串应答")
    void testDeviceIdAndHeaderInjection() {
        String reqId = IdUtil.fastSimpleUUID();
        String json = "{\"command\":\"DEVICE_PING\",\"requestId\":\"" + reqId + "\",\"deviceId\":\"DEV-SN-999\",\"clientVersion\":\"v1.2.0\"}\n";
        TcpPacket reqPacket = TcpPacket.of("DEVICE_PING", reqId, TcpPayload.of(json));
        reqPacket.setDeviceId("DEV-SN-999");
        reqPacket.setHeader("clientVersion", "v1.2.0");

        String reply = clientTemplate.request(reqPacket, String.class, Duration.ofSeconds(3));

        assertThat(reply).isEqualTo("PONG:DEV-SN-999:v1.2.0");
    }

    @Test
    @DisplayName("验证无返回值 @TcpMapping 单向异步消息处理")
    void testOneWayNotify() throws Exception {
        String json = "{\"command\":\"NOTIFY_ALERT\",\"alertMessage\":\"SYSTEM_WARNING\"}\n";
        TcpPacket reqPacket = TcpPacket.of("NOTIFY_ALERT", null, TcpPayload.of(json));

        clientTemplate.send(reqPacket);

        boolean reached = orderListener.notifyLatch.await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
        assertThat(orderListener.lastNotifyPayload.get()).contains("SYSTEM_WARNING");
        assertThat(orderListener.lastNotifyThread.get()).startsWith("tcp-biz-worker-");
    }

    @Test
    @DisplayName("验证函数式 TcpMessageHandler SPI 扫描与分发消费")
    void testSpiHandlerExecution() throws Exception {
        String json = "{\"command\":\"DEVICE_STATUS\",\"deviceId\":\"DEV-101\",\"status\":\"ONLINE\"}\n";
        TcpPacket reqPacket = TcpPacket.of("DEVICE_STATUS", null, TcpPayload.of(json));

        clientTemplate.send(reqPacket);

        boolean reached = statusHandler.latch.await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
        assertThat(statusHandler.lastReceivedPayload.get()).isNotNull();
        assertThat(statusHandler.lastReceivedPayload.get().getDeviceId()).isEqualTo("DEV-101");
        assertThat(statusHandler.lastReceivedPayload.get().getStatus()).isEqualTo("ONLINE");
        assertThat(statusHandler.lastThread.get()).startsWith("tcp-biz-worker-");
    }

    @Test
    @DisplayName("真实 TCP 链路：四维上下文透传清理、@Valid 快速失败与 sync/async RPC 错误回执不超时")
    void testRealTcpContextValidationAndFastErrorReply() throws Exception {
        // 1. 经由真实 Socket 报文 JSON 扩展头发送四维上下文，验证服务端监听器激活并在完成后零残留
        String reqIdCtx = IdUtil.fastSimpleUUID();
        Map<String, Object> ctxHeaders = Map.of(
                MessageHeaderConstants.TRACE_ID, "real-trace-001",
                MessageHeaderConstants.TENANT_ID, "real-tenant-99",
                MessageHeaderConstants.USER_ID, "real-user-42",
                MessageHeaderConstants.IGNORE_TENANT, "true"
        );
        Map<String, Object> ctxPayload = Map.of(
                "command", "REAL_CTX_ECHO",
                "requestId", reqIdCtx,
                "headers", ctxHeaders
        );
        TcpPacket ctxReq = TcpPacket.of("REAL_CTX_ECHO", reqIdCtx,
                TcpPayload.of(JsonUtil.toJsonString(ctxPayload) + "\n"));
        TcpPacket ctxResp = clientTemplate.request(ctxReq, Duration.ofSeconds(3));
        assertThat(ctxResp).isNotNull();
        Map<String, Object> ctxRespMap = JsonUtil.parseObject(ctxResp.getPayload().asString().trim(), Map.class);
        assertThat(ctxRespMap)
                .containsEntry("traceId", "real-trace-001")
                .containsEntry("tenantId", "real-tenant-99")
                .containsEntry("userId", "real-user-42")
                .containsEntry("ignoreTenant", true);

        // 在业务线程池执行一次后续普通任务，确认工作线程四维上下文已清理无残留
        CountDownLatch cleanLatch = new CountDownLatch(1);
        AtomicReference<String> leftoverTenant = new AtomicReference<>("dirty");
        AtomicReference<String> leftoverUser = new AtomicReference<>("dirty");
        AtomicBoolean leftoverIgnore = new AtomicBoolean(true);
        businessExecutor.execute(() -> {
            leftoverTenant.set(TenantContextHolder.getTenantId());
            leftoverUser.set(UserContextHolder.get());
            leftoverIgnore.set(TenantContextHolder.isIgnoreTenant());
            cleanLatch.countDown();
        });
        assertThat(cleanLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(leftoverTenant.get()).isNull();
        assertThat(leftoverUser.get()).isNull();
        assertThat(leftoverIgnore.get()).isFalse();

        // 2. 同步 RPC 请求触发 @Valid 校验失败 -> 在远小于超时窗口(5s)内立即收到 ERROR 回执帧，且业务方法未被执行
        String invalidReqId = IdUtil.fastSimpleUUID();
        TcpPacket invalidRpcPacket = TcpPacket.of("ORDER_VALIDATED", invalidReqId,
                TcpPayload.of("{\"command\":\"ORDER_VALIDATED\",\"requestId\":\"" + invalidReqId + "\",\"orderId\":\"\",\"amount\":-5}\n"));
        long startNanos = System.nanoTime();
        TcpPacket syncErrResp = clientTemplate.request(invalidRpcPacket, Duration.ofSeconds(5));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(orderListener.validatedMethodEntered.get()).isFalse();
        assertThat(elapsedMs).isLessThan(2000L);
        assertThat(syncErrResp).isNotNull();
        assertThat(syncErrResp.getRequestId()).isEqualTo(invalidReqId);
        Map<String, Object> syncErrMap = JsonUtil.parseObject(syncErrResp.getPayload().asString().trim(), Map.class);
        assertThat(syncErrMap).containsEntry("requestId", invalidReqId)
                .containsEntry("command", "ORDER_VALIDATED")
                .containsEntry("status", "ERROR");
        assertThat(String.valueOf(syncErrMap.get("message"))).isNotBlank();

        // 3. 异步 RPC 请求触发业务异常 -> requestAsync 立即完成并返回 ERROR 回执帧
        String asyncThrowReqId = IdUtil.fastSimpleUUID();
        TcpPacket throwRpcPacket = TcpPacket.of("ORDER_THROW", asyncThrowReqId,
                TcpPayload.of("{\"command\":\"ORDER_THROW\",\"requestId\":\"" + asyncThrowReqId + "\"}\n"));
        CompletableFuture<TcpPacket> asyncFuture = clientTemplate.requestAsync(throwRpcPacket, Duration.ofSeconds(5));
        TcpPacket asyncErrResp = asyncFuture.get(2, TimeUnit.SECONDS);

        Map<String, Object> asyncErrMap = JsonUtil.parseObject(asyncErrResp.getPayload().asString().trim(), Map.class);
        assertThat(asyncErrMap).containsEntry("requestId", asyncThrowReqId)
                .containsEntry("command", "ORDER_THROW")
                .containsEntry("status", "ERROR");
        assertThat(String.valueOf(asyncErrMap.get("message"))).contains("订单服务模拟异常");
    }

    // ================================= 样例业务组件与 DTO =================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderCreateRequest {
        private String orderId;
        private Long amount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderCreateResponse {
        private String orderId;
        private String status;
        private String traceId;
        private String workerThread;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceStatusDTO {
        private String deviceId;
        private String status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidatedOrderRequest {
        @NotBlank(message = "orderId 不能为空")
        private String orderId;

        @Min(value = 1, message = "amount 必须大于0")
        private Long amount;
    }

    @TcpListener
    public static class SampleOrderListener {

        final CountDownLatch notifyLatch = new CountDownLatch(1);
        final AtomicReference<String> lastNotifyPayload = new AtomicReference<>();
        final AtomicReference<String> lastNotifyThread = new AtomicReference<>();
        final AtomicBoolean validatedMethodEntered = new AtomicBoolean(false);

        @TcpMapping(command = "ORDER_CREATE")
        public OrderCreateResponse handleCreateOrder(TcpSession session, OrderCreateRequest request) {
            return new OrderCreateResponse(
                    request.getOrderId(),
                    "CREATED",
                    MDC.get("traceId"),
                    Thread.currentThread().getName()
            );
        }

        @TcpMapping(command = "REAL_CTX_ECHO")
        public Map<String, Object> handleContextEcho(String payload) {
            Map<String, Object> res = new java.util.LinkedHashMap<>();
            res.put("traceId", MDC.get("traceId"));
            res.put("tenantId", TenantContextHolder.getTenantId());
            res.put("userId", UserContextHolder.get());
            res.put("ignoreTenant", TenantContextHolder.isIgnoreTenant());
            return res;
        }

        @TcpMapping(command = "ORDER_VALIDATED")
        public Map<String, Object> handleValidatedOrder(@Valid ValidatedOrderRequest request) {
            validatedMethodEntered.set(true);
            return Map.of("orderId", request.getOrderId(), "status", "VALIDATED_OK");
        }

        @TcpMapping(command = "ORDER_THROW")
        public Map<String, Object> handleThrowOrder(String payload) {
            throw new IllegalStateException("订单服务模拟异常");
        }

        @TcpMapping(command = "DEVICE_PING")
        public String handlePing(@TcpDeviceId String deviceId, @TcpHeader("clientVersion") String clientVersion) {
            return "PONG:" + deviceId + ":" + clientVersion;
        }

        @TcpMapping(command = "NOTIFY_ALERT")
        public void handleAlert(String payload) {
            lastNotifyPayload.set(payload);
            lastNotifyThread.set(Thread.currentThread().getName());
            notifyLatch.countDown();
        }
    }

    public static class SampleDeviceStatusHandler implements TcpMessageHandler<DeviceStatusDTO> {

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<DeviceStatusDTO> lastReceivedPayload = new AtomicReference<>();
        final AtomicReference<String> lastThread = new AtomicReference<>();

        @Override
        public String getCommand() {
            return "DEVICE_STATUS";
        }

        @Override
        public void handle(TcpSession session, DeviceStatusDTO payload, TcpPacket packet) {
            lastReceivedPayload.set(payload);
            lastThread.set(Thread.currentThread().getName());
            latch.countDown();
        }
    }
}
