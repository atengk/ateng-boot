package io.github.atengk.boot.tcp.route;

import io.github.atengk.boot.tcp.annotation.TcpDeviceId;
import io.github.atengk.boot.tcp.annotation.TcpHeader;
import io.github.atengk.boot.tcp.annotation.TcpListener;
import io.github.atengk.boot.tcp.annotation.TcpMapping;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.spi.TcpMessageHandler;
import io.netty.channel.embedded.EmbeddedChannel;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
        context.registerBean("mockController", MockController.class, () -> mockController);
        context.registerBean("mockSpiHandler", MockSpiHandler.class, () -> mockSpiHandler);
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

    @TcpListener
    public static class MockController {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile TcpSession receivedSession;
        volatile String receivedDeviceId;
        volatile String receivedToken;
        volatile QueryParam receivedParam;
        volatile String receivedThreadName;
        volatile String receivedTraceId;

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
