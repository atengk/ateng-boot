package io.github.atengk.boot.tcp.route;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.annotation.TcpDeviceId;
import io.github.atengk.boot.tcp.annotation.TcpHeader;
import io.github.atengk.boot.tcp.annotation.TcpListener;
import io.github.atengk.boot.tcp.annotation.TcpMapping;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.spi.TcpMessageHandler;
import io.netty.channel.embedded.EmbeddedChannel;
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
import org.springframework.validation.annotation.Validated;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP 分发器单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 分发器单元测试")
class TcpDispatcherTest {

    private ThreadPoolTaskExecutor executor;
    private GenericApplicationContext context;
    private TcpDispatcher dispatcher;
    private MockController mockController;
    private MockSpiHandler mockSpiHandler;
    private ClassValidatedController classValidatedController;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("test-tcp-worker-");
        executor.initialize();

        context = new GenericApplicationContext();
        mockController = new MockController();
        mockSpiHandler = new MockSpiHandler();
        classValidatedController = new ClassValidatedController();
        context.registerBean("mockController", MockController.class, () -> mockController);
        context.registerBean("mockSpiHandler", MockSpiHandler.class, () -> mockSpiHandler);
        context.registerBean("classValidatedController", ClassValidatedController.class, () -> classValidatedController);
        context.refresh();

        dispatcher = new TcpDispatcher(context, new JsonRouteKeyExtractor(), executor);
        dispatcher.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        if (context != null) {
            context.close();
        }
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("测试注解路由参数注入、异步隔离与自动回包")
    void testAnnotationRoutingAndAsyncIsolation() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("sess-1", channel);

        TcpPacket packet = TcpPacket.ofJson("USER_QUERY", new QueryParam("user-100"));
        packet.setRequestId("req-999");
        packet.setDeviceId("dev-888");
        packet.getHeaders().put("token", "secret-token");

        dispatcher.dispatch(session, packet);

        boolean reached = mockController.latch.await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();

        // 验证参数正确解析注入
        assertThat(mockController.receivedSession).isSameAs(session);
        assertThat(mockController.receivedDeviceId).isEqualTo("dev-888");
        assertThat(mockController.receivedToken).isEqualTo("secret-token");
        assertThat(mockController.receivedParam).isNotNull();
        assertThat(mockController.receivedParam.getUserId()).isEqualTo("user-100");

        // 验证异步隔离：在线程池执行
        assertThat(mockController.receivedThreadName).startsWith("test-tcp-worker-");
        // 验证 MDC traceId 穿透成功
        assertThat(mockController.receivedTraceId).isEqualTo("req-999");

        // 等待轮询回写通道中的报文
        TcpPacket outbound = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            outbound = channel.readOutbound();
            if (outbound != null) {
                break;
            }
            Thread.sleep(20);
        }

        assertThat(outbound).isNotNull();
        assertThat(outbound.getCommand()).isEqualTo("USER_QUERY");
        assertThat(outbound.getRequestId()).isEqualTo("req-999");
        assertThat(outbound.getDeviceId()).isEqualTo("dev-888");
        assertThat(outbound.getPayload().asText()).contains("Alice");
    }

    @Test
    @DisplayName("测试 SPI 函数式消息处理器分发")
    void testSpiHandlerRouting() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("sess-2", channel);

        TcpPacket packet = TcpPacket.ofJson("DEVICE_HEARTBEAT", new HeartbeatDTO("ONLINE"));
        packet.setRequestId("req-heartbeat");

        dispatcher.dispatch(session, packet);

        boolean reached = mockSpiHandler.latch.await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
        assertThat(mockSpiHandler.receivedSession).isSameAs(session);
        assertThat(mockSpiHandler.receivedPayload).isNotNull();
        assertThat(mockSpiHandler.receivedPayload.getStatus()).isEqualTo("ONLINE");
        assertThat(mockSpiHandler.receivedThreadName).startsWith("test-tcp-worker-");
    }

    @Test
    @DisplayName("测试未知命令字平滑跳过")
    void testUnknownCommandIgnored() {
        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("sess-3", channel);
        TcpPacket packet = TcpPacket.ofJson("UNKNOWN_COMMAND", "some data");

        dispatcher.dispatch(session, packet);
        assertThat((TcpPacket) channel.readOutbound()).isNull();
    }

    @Test
    @DisplayName("四维上下文透传与 Netty IO 线程零污染及工作线程零残留清理")
    void testFourDimensionalContextPropagationAndZeroLeakage() throws Exception {
        // 使用单线程受管执行器确定性验证工作线程原始现场恢复与零残留
        ThreadPoolTaskExecutor singleWorker = new ThreadPoolTaskExecutor();
        singleWorker.setCorePoolSize(1);
        singleWorker.setMaxPoolSize(1);
        singleWorker.setThreadNamePrefix("test-single-worker-");
        singleWorker.initialize();
        try {
            TcpDispatcher isolatedDispatcher = new TcpDispatcher(context, new JsonRouteKeyExtractor(), singleWorker);
            isolatedDispatcher.afterPropertiesSet();

            // 1. 预先在工作线程植入历史原始现场
            CountDownLatch seedLatch = new CountDownLatch(1);
            singleWorker.execute(() -> {
                MDC.put("traceId", "worker-orig-trace");
                TenantContextHolder.setTenantId("worker-orig-tenant");
                UserContextHolder.set("worker-orig-user");
                TenantContextHolder.setIgnoreTenant(false);
                seedLatch.countDown();
            });
            assertThat(seedLatch.await(3, TimeUnit.SECONDS)).isTrue();

            EmbeddedChannel channel = new EmbeddedChannel();
            TcpSession session = new TcpSession("sess-ctx", channel);
            session.setAttribute(MessageHeaderConstants.TENANT_ID, "session-tenant-fallback");
            session.setAttribute(MessageHeaderConstants.USER_ID, "session-user-01");

            TcpPacket packet = TcpPacket.ofJson("CONTEXT_ECHO", Map.of("msg", "hello"));
            packet.setHeader(MessageHeaderConstants.TRACE_ID, "trace-pkt-777");
            packet.setHeader(MessageHeaderConstants.TENANT_ID, "tenant-pkt-888");
            packet.setHeader(MessageHeaderConstants.IGNORE_TENANT, "true");

            // 调用前 Netty IO 线程预设不同上下文（验证调用线程本身不被污染或修改）
            MDC.put("traceId", "io-orig-trace");
            TenantContextHolder.setTenantId("io-orig-tenant");
            UserContextHolder.set("io-orig-user");

            isolatedDispatcher.dispatch(session, packet);

            assertThat(mockController.ctxLatch.await(3, TimeUnit.SECONDS)).isTrue();
            // Netty IO 线程现场保持原样未受四维注入污染
            assertThat(MDC.get("traceId")).isEqualTo("io-orig-trace");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("io-orig-tenant");
            assertThat(UserContextHolder.get()).isEqualTo("io-orig-user");
            assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();

            // 业务线程准确接收到合并后的四维上下文（packet 优先，session 补充）
            assertThat(mockController.ctxTraceId.get()).isEqualTo("trace-pkt-777");
            assertThat(mockController.ctxTenantId.get()).isEqualTo("tenant-pkt-888");
            assertThat(mockController.ctxUserId.get()).isEqualTo("session-user-01");
            assertThat(mockController.ctxIgnoreTenant.get()).isTrue();

            // 2. 验证工作线程精确恢复其原始现场（未残留 pkt 上下文），并在清空后再次派发也无残留
            AtomicReference<String> afterTraceOnWorker = new AtomicReference<>();
            AtomicReference<String> afterTenantOnWorker = new AtomicReference<>();
            AtomicReference<String> afterUserOnWorker = new AtomicReference<>();
            AtomicBoolean afterIgnoreOnWorker = new AtomicBoolean(true);

            CountDownLatch restoreCheckLatch = new CountDownLatch(1);
            singleWorker.execute(() -> {
                afterTraceOnWorker.set(MDC.get("traceId"));
                afterTenantOnWorker.set(TenantContextHolder.getTenantId());
                afterUserOnWorker.set(UserContextHolder.get());
                afterIgnoreOnWorker.set(TenantContextHolder.isIgnoreTenant());
                // 清空该线程原始现场，准备第二轮零残留验证
                MDC.clear();
                TenantContextHolder.clear();
                UserContextHolder.clear();
                restoreCheckLatch.countDown();
            });
            assertThat(restoreCheckLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(afterTraceOnWorker.get()).isEqualTo("worker-orig-trace");
            assertThat(afterTenantOnWorker.get()).isEqualTo("worker-orig-tenant");
            assertThat(afterUserOnWorker.get()).isEqualTo("worker-orig-user");
            assertThat(afterIgnoreOnWorker.get()).isFalse();

            // 3. 干净工作线程再次派发后，确认执行结束零残留
            isolatedDispatcher.dispatch(session, packet);
            CountDownLatch zeroCheckLatch = new CountDownLatch(1);
            singleWorker.execute(() -> {
                afterTraceOnWorker.set(MDC.get("traceId"));
                afterTenantOnWorker.set(TenantContextHolder.getTenantId());
                afterUserOnWorker.set(UserContextHolder.get());
                afterIgnoreOnWorker.set(TenantContextHolder.isIgnoreTenant());
                zeroCheckLatch.countDown();
            });
            assertThat(zeroCheckLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(afterTraceOnWorker.get()).isNull();
            assertThat(afterTenantOnWorker.get()).isNull();
            assertThat(afterUserOnWorker.get()).isNull();
            assertThat(afterIgnoreOnWorker.get()).isFalse();
        } finally {
            singleWorker.shutdown();
        }
    }

    @Test
    @DisplayName("动态路由对称管理 registerHandler / unregisterHandler / getRegisteredCommands")
    void testDynamicRouteManagement() throws Exception {
        assertThat(dispatcher.getRegisteredCommands())
                .contains("USER_QUERY", "DEVICE_HEARTBEAT", "CONTEXT_ECHO", "VALIDATED_CMD");

        assertThatThrownBy(() -> dispatcher.registerHandler(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.registerHandler(new TcpMessageHandler<String>() {
            @Override
            public String getCommand() {
                return " ";
            }

            @Override
            public void handle(TcpSession session, String payload, TcpPacket packet) {
            }
        })).isInstanceOf(IllegalArgumentException.class);

        CountDownLatch dynLatch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        AtomicReference<String> dynReceived = new AtomicReference<>();
        dispatcher.registerHandler(new TcpMessageHandler<String>() {
            @Override
            public String getCommand() {
                return "DYNAMIC_CMD";
            }

            @Override
            public void handle(TcpSession session, String payload, TcpPacket packet) {
                callCount.incrementAndGet();
                dynReceived.set(payload);
                dynLatch.countDown();
            }
        });
        assertThat(dispatcher.getRegisteredCommands()).contains("DYNAMIC_CMD");

        EmbeddedChannel channel = new EmbeddedChannel();
        TcpSession session = new TcpSession("sess-dyn", channel);
        dispatcher.dispatch(session, TcpPacket.of("DYNAMIC_CMD", TcpPayload.of("{\"command\":\"DYNAMIC_CMD\"}")));
        assertThat(dynLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(dynReceived.get()).contains("DYNAMIC_CMD");

        assertThat(dispatcher.unregisterHandler("DYNAMIC_CMD")).isTrue();
        assertThat(dispatcher.unregisterHandler("DYNAMIC_CMD")).isFalse();
        assertThat(dispatcher.getRegisteredCommands()).doesNotContain("DYNAMIC_CMD");

        dispatcher.dispatch(session, TcpPacket.of("DYNAMIC_CMD", TcpPayload.of("{\"command\":\"DYNAMIC_CMD\"}")));
        Thread.sleep(100);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("@Valid 与 @Validated 前置校验拦截及 RPC 异常快速错误回执")
    void testValidationFastFailAndRpcErrorResponseFrame() throws Exception {
        // 1. 参数 @Valid 校验非法，拦截不进入业务方法并回写结构化 ERROR 回执帧（保留 \n 定界符）
        EmbeddedChannel ch1 = new EmbeddedChannel();
        TcpSession session1 = new TcpSession("sess-val-1", ch1);
        TcpPacket invalidPacket = TcpPacket.of("VALIDATED_CMD", "req-err-1",
                TcpPayload.of("{\"command\":\"VALIDATED_CMD\",\"requestId\":\"req-err-1\",\"deviceId\":\"\",\"speed\":-1}\n"));
        dispatcher.dispatch(session1, invalidPacket);

        TcpPacket errResp1 = pollOutbound(ch1);
        assertThat(mockController.validatedInvoked.get()).isFalse();
        assertThat(errResp1).isNotNull();
        assertThat(errResp1.getCommand()).isEqualTo("VALIDATED_CMD");
        assertThat(errResp1.getRequestId()).isEqualTo("req-err-1");
        String respText1 = errResp1.getPayload().asString();
        assertThat(respText1).endsWith("\n");
        Map<String, Object> errMap1 = JsonUtil.parseObject(respText1.trim(), Map.class);
        assertThat(errMap1).containsEntry("requestId", "req-err-1")
                .containsEntry("command", "VALIDATED_CMD")
                .containsEntry("status", "ERROR");
        assertThat(String.valueOf(errMap1.get("message"))).isNotBlank();

        // 2. 类级别 @Validated 校验拦截
        EmbeddedChannel ch2 = new EmbeddedChannel();
        TcpSession session2 = new TcpSession("sess-val-2", ch2);
        TcpPacket classInvalidPacket = TcpPacket.of("CLASS_VALIDATED_CMD", "req-err-2",
                TcpPayload.of("{\"command\":\"CLASS_VALIDATED_CMD\",\"requestId\":\"req-err-2\",\"deviceId\":\"\",\"speed\":10}\r\n"));
        dispatcher.dispatch(session2, classInvalidPacket);

        TcpPacket errResp2 = pollOutbound(ch2);
        assertThat(classValidatedController.invoked.get()).isFalse();
        assertThat(errResp2).isNotNull();
        assertThat(errResp2.getPayload().asString()).endsWith("\r\n");
        Map<String, Object> errMap2 = JsonUtil.parseObject(errResp2.getPayload().asString().trim(), Map.class);
        assertThat(errMap2).containsEntry("status", "ERROR").containsEntry("requestId", "req-err-2");

        // 3. 业务方法抛出运行时异常且带 requestId -> 快速返回结构化错误回执帧
        EmbeddedChannel ch3 = new EmbeddedChannel();
        TcpSession session3 = new TcpSession("sess-err-3", ch3);
        TcpPacket throwPacket = TcpPacket.of("THROW_CMD", "req-err-3",
                TcpPayload.of("{\"command\":\"THROW_CMD\",\"requestId\":\"req-err-3\"}\n"));
        dispatcher.dispatch(session3, throwPacket);

        TcpPacket errResp3 = pollOutbound(ch3);
        assertThat(errResp3).isNotNull();
        Map<String, Object> errMap3 = JsonUtil.parseObject(errResp3.getPayload().asString().trim(), Map.class);
        assertThat(errMap3).containsEntry("requestId", "req-err-3")
                .containsEntry("command", "THROW_CMD")
                .containsEntry("status", "ERROR");
        assertThat(String.valueOf(errMap3.get("message"))).contains("模拟业务处理失败");

        // 4. 合法 DTO 正常放行对照验证
        EmbeddedChannel ch4 = new EmbeddedChannel();
        TcpSession session4 = new TcpSession("sess-val-ok", ch4);
        TcpPacket validPacket = TcpPacket.of("VALIDATED_CMD", "req-ok-4",
                TcpPayload.of("{\"command\":\"VALIDATED_CMD\",\"requestId\":\"req-ok-4\",\"deviceId\":\"DEV-VALID-1\",\"speed\":60}\n"));
        dispatcher.dispatch(session4, validPacket);

        TcpPacket okResp = pollOutbound(ch4);
        assertThat(okResp).isNotNull();
        assertThat(mockController.validatedInvoked.get()).isTrue();
        Map<String, Object> okMap = JsonUtil.parseObject(okResp.getPayload().asString().trim(), Map.class);
        assertThat(okMap).containsEntry("requestId", "req-ok-4").containsEntry("status", "OK");
    }

    private static TcpPacket pollOutbound(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            TcpPacket outbound = channel.readOutbound();
            if (outbound != null) {
                return outbound;
            }
            Thread.sleep(20);
        }
        return null;
    }

    // ================================= 辅助测试组件 =================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryParam {
        private String userId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserVO {
        private String userId;
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HeartbeatDTO {
        private String status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidatedCommandDTO {
        @NotBlank(message = "deviceId 不能为空")
        private String deviceId;

        @Min(value = 0, message = "speed 不能为负数")
        private int speed;
    }

    @TcpListener
    public static class MockController {
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch ctxLatch = new CountDownLatch(1);
        final AtomicBoolean validatedInvoked = new AtomicBoolean(false);
        volatile TcpSession receivedSession;
        volatile String receivedDeviceId;
        volatile String receivedToken;
        volatile QueryParam receivedParam;
        volatile String receivedThreadName;
        volatile String receivedTraceId;
        final AtomicReference<String> ctxTraceId = new AtomicReference<>();
        final AtomicReference<String> ctxTenantId = new AtomicReference<>();
        final AtomicReference<String> ctxUserId = new AtomicReference<>();
        final AtomicBoolean ctxIgnoreTenant = new AtomicBoolean(false);

        @TcpMapping("USER_QUERY")
        public UserVO handleUserQuery(TcpSession session,
                                      @TcpDeviceId String deviceId,
                                      @TcpHeader("token") String token,
                                      QueryParam param) {
            this.receivedSession = session;
            this.receivedDeviceId = deviceId;
            this.receivedToken = token;
            this.receivedParam = param;
            this.receivedThreadName = Thread.currentThread().getName();
            this.receivedTraceId = MDC.get("traceId");
            latch.countDown();
            return new UserVO(param.getUserId(), "Alice");
        }

        @TcpMapping("CONTEXT_ECHO")
        public void handleContextEcho(String payload) {
            ctxTraceId.set(MDC.get("traceId"));
            ctxTenantId.set(TenantContextHolder.getTenantId());
            ctxUserId.set(UserContextHolder.get());
            ctxIgnoreTenant.set(TenantContextHolder.isIgnoreTenant());
            ctxLatch.countDown();
        }

        @TcpMapping("VALIDATED_CMD")
        public Map<String, Object> handleValidated(@Valid ValidatedCommandDTO dto) {
            validatedInvoked.set(true);
            return Map.of("status", "OK");
        }

        @TcpMapping("THROW_CMD")
        public Map<String, Object> handleThrow(String payload) {
            throw new IllegalStateException("模拟业务处理失败");
        }
    }

    @Validated
    @TcpListener
    public static class ClassValidatedController {
        final AtomicBoolean invoked = new AtomicBoolean(false);

        @TcpMapping("CLASS_VALIDATED_CMD")
        public Map<String, Object> handle(ValidatedCommandDTO dto) {
            invoked.set(true);
            return Map.of("status", "OK");
        }
    }

    public static class MockSpiHandler implements TcpMessageHandler<HeartbeatDTO> {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile TcpSession receivedSession;
        volatile HeartbeatDTO receivedPayload;
        volatile String receivedThreadName;

        @Override
        public String getCommand() {
            return "DEVICE_HEARTBEAT";
        }

        @Override
        public void handle(TcpSession session, HeartbeatDTO payload, TcpPacket packet) {
            this.receivedSession = session;
            this.receivedPayload = payload;
            this.receivedThreadName = Thread.currentThread().getName();
            latch.countDown();
        }
    }
}
