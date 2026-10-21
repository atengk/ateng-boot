package io.github.atengk.boot.realtime.heartbeat;

import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 幽灵连接与死链安全排空心跳探针单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("GhostConnectionDetector 死链排空探针测试")
class GhostConnectionDetectorTest {

    @Test
    @DisplayName("活跃会话定期发送保底心跳探针 (WS Ping 协议帧 / SSE keepalive 注释)")
    void testActiveSessionHeartbeatProbe() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getHeartbeat().setInterval(Duration.ofSeconds(25));
        properties.getHeartbeat().setIdleTimeout(Duration.ofSeconds(60));

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);

        // 注册活跃 WS 会话
        SessionKey wsKey = SessionKey.of("user-ws", "client-ws");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession wsSession = RealtimeSession.ofWebSocket(wsKey, ws);
        sessionManager.registerSession(wsSession);

        // 注册活跃 SSE 会话
        SessionKey sseKey = SessionKey.of("user-sse", "client-sse");
        SseEmitter sse = mock(SseEmitter.class);
        RealtimeSession sseSession = RealtimeSession.ofSse(sseKey, sse);
        sessionManager.registerSession(sseSession);

        GhostConnectionDetector detector = new GhostConnectionDetector(properties, sessionManager);

        // 执行单次探测
        detector.detectAndHeartbeat();

        // 验证 WS 收到 Ping 帧，SSE 收到 keepalive 注释，会话均未关闭
        verify(ws, times(1)).sendMessage(any(PingMessage.class));
        verify(sse, times(1)).send(any(SseEmitter.SseEventBuilder.class));

        assertThat(sessionManager.containsSession(wsKey)).isTrue();
        assertThat(sessionManager.containsSession(sseKey)).isTrue();
    }

    @Test
    @DisplayName("空闲超时连接判定为幽灵死链，强制执行物理 Close 释放句柄")
    void testIdleTimeoutForcibleClose() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getHeartbeat().setInterval(Duration.ofMillis(50));
        properties.getHeartbeat().setIdleTimeout(Duration.ofMillis(200));

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);

        SessionKey deadWsKey = SessionKey.of("user-dead", "client-dead");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession deadSession = RealtimeSession.ofWebSocket(deadWsKey, ws);
        sessionManager.registerSession(deadSession);

        // 人工模拟该会话 10 秒前无动静（超越 200ms idleTimeout）
        ReflectionTestUtils.setField(deadSession, "lastActiveTime", System.currentTimeMillis() - 10_000L);

        GhostConnectionDetector detector = new GhostConnectionDetector(properties, sessionManager);

        // 执行单次探测
        detector.detectAndHeartbeat();

        // 验证该假死会话被强制执行排空关闭，关闭码为 4004 (IDLE_TIMEOUT)
        verify(ws, times(1)).close(RealtimeCloseStatus.IDLE_TIMEOUT);
        assertThat(sessionManager.containsSession(deadWsKey)).isFalse();
        assertThat(sessionManager.getOnlineCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("SmartLifecycle 启停与状态验证")
    void testLifecycle() {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        GhostConnectionDetector detector = new GhostConnectionDetector(properties, sessionManager);

        assertThat(detector.isRunning()).isFalse();

        detector.start();
        assertThat(detector.isRunning()).isTrue();

        detector.stop();
        assertThat(detector.isRunning()).isFalse();
    }
}
