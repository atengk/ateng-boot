package io.github.atengk.boot.realtime.shutdown;

import io.github.atengk.boot.realtime.auth.AtengHandshakeInterceptor;
import io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.cluster.RedissonClusterMetricsReporter;
import io.github.atengk.boot.realtime.cluster.RedissonRealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.sse.AtengSseController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 云原生优雅停机排空机制测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeGracefulShutdownHandler 优雅停机四阶排空测试")
class RealtimeGracefulShutdownTest {

    private RealtimeProperties properties;
    private RealtimeSessionManager sessionManager;
    private RealtimeClusterBroadcaster clusterBroadcaster;
    private RedissonClusterMetricsReporter metricsReporter;
    private RealtimeGracefulShutdownHandler shutdownHandler;

    @BeforeEach
    void setUp() {
        properties = new RealtimeProperties();
        // 设置极短的静默期和排空超时以加速单测执行
        properties.getShutdown().setQuietPeriod(Duration.ofMillis(10));
        properties.getShutdown().setDrainTimeout(Duration.ofMillis(20));

        sessionManager = new RealtimeSessionManager(properties);
        clusterBroadcaster = mock(RedissonRealtimeClusterBroadcaster.class);
        metricsReporter = mock(RedissonClusterMetricsReporter.class);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("broadcaster", clusterBroadcaster);
        beanFactory.addBean("metricsReporter", metricsReporter);

        shutdownHandler = new RealtimeGracefulShutdownHandler(
                properties,
                sessionManager,
                beanFactory.getBeanProvider(RealtimeClusterBroadcaster.class),
                beanFactory.getBeanProvider(RedissonClusterMetricsReporter.class)
        );
    }

    @AfterEach
    void tearDown() {
        for (RealtimeSession s : sessionManager.getAllSessions()) {
            sessionManager.removeAndClose(s.getSessionKey(), CloseStatus.NORMAL);
        }
    }

    @Test
    @DisplayName("SmartLifecycle 契约验证: 优先级为 Integer.MAX_VALUE - 1000 且自启配置生效")
    void testSmartLifecycleContract() {
        assertThat(shutdownHandler.getPhase()).isEqualTo(Integer.MAX_VALUE - 1000);
        assertThat(shutdownHandler.isAutoStartup()).isTrue();

        properties.getShutdown().setEnabled(false);
        assertThat(shutdownHandler.isAutoStartup()).isFalse();
    }

    @Test
    @DisplayName("四阶渐进排空流转: 握手熔断 -> 1012 状态码下发 -> 缓冲等待 -> 物理清理与回调")
    void testFourStageGracefulShutdownFlow() throws Exception {
        // 1. 注册活跃 WebSocket 会话
        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        SessionKey key = SessionKey.of("user-shutdown", "client-1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key, mockWs));

        shutdownHandler.start();
        assertThat(shutdownHandler.isRunning()).isTrue();
        assertThat(shutdownHandler.isDraining()).isFalse();

        // 2. 触发优雅停机
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        shutdownHandler.stop(() -> callbackInvoked.set(true));

        // 验证 Phase 2: 本地活跃会话被以 1012 (SERVICE_RESTART) 状态码切断
        assertThat(sessionManager.containsSession(key)).isFalse();
        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(mockWs, times(1)).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(RealtimeCloseStatus.SERVICE_RESTART.getCode());
        assertThat(statusCaptor.getValue().getReason()).isEqualTo(RealtimeCloseStatus.SERVICE_RESTART.getReason());

        // 验证 Phase 4: 资源注销与回调触发
        verify(metricsReporter, times(1)).cleanup();
        verify((RedissonRealtimeClusterBroadcaster) clusterBroadcaster, times(1)).destroy();
        assertThat(callbackInvoked.get()).isTrue();
        assertThat(shutdownHandler.isRunning()).isFalse();
        assertThat(shutdownHandler.isDraining()).isFalse();
    }

    @Test
    @DisplayName("Phase 1 握手熔断: 当处于停机排空状态时，WebSocket 握手拦截器返回 HTTP 503 拒绝新接入")
    void testWebSocketHandshakeIngressCutoff() throws Exception {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("shutdownHandler", shutdownHandler);

        AtengHandshakeInterceptor interceptor = new AtengHandshakeInterceptor(
                properties,
                beanFactory.getBeanProvider(io.github.atengk.boot.realtime.auth.RealtimeAuthHandshakeProvider.class),
                beanFactory.getBeanProvider(RealtimeGracefulShutdownHandler.class)
        );

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/ws/realtime"));
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);

        // 未排空时正常处理（进入后续鉴权）
        // 模拟已进入排空流程
        shutdownHandler.start();
        // 模拟反射/内部设置 draining 状态
        java.lang.reflect.Field drainingField = RealtimeGracefulShutdownHandler.class.getDeclaredField("draining");
        drainingField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) drainingField.get(shutdownHandler)).set(true);

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());
        assertThat(allowed).isFalse();
        verify(response, times(1)).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Phase 1 握手熔断: 当处于停机排空状态时，SSE 控制器抛出 HTTP 503 异常拒绝新接入")
    void testSseIngressCutoff() throws Exception {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("shutdownHandler", shutdownHandler);

        AtengSseController sseController = new AtengSseController(
                properties,
                sessionManager,
                beanFactory.getBeanProvider(io.github.atengk.boot.realtime.auth.RealtimeAuthHandshakeProvider.class),
                beanFactory.getBeanProvider(RealtimeGracefulShutdownHandler.class)
        );

        shutdownHandler.start();
        java.lang.reflect.Field drainingField = RealtimeGracefulShutdownHandler.class.getDeclaredField("draining");
        drainingField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) drainingField.get(shutdownHandler)).set(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThatThrownBy(() -> sseController.connect("c1", "u1", "default", null, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }
}
