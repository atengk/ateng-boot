package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.dispatcher.RealtimeMessageDispatcher;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.*;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 核心原生 WebSocket 处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("AtengWebSocketHandler 消息处理器测试")
class AtengWebSocketHandlerTest {

    private final AtengWebSocketHandler handler = new AtengWebSocketHandler();

    @Test
    void testPlainPingPong() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-001");

        handler.handleTextMessage(session, new TextMessage("ping"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).isEqualTo("pong");
    }

    @Test
    void testJsonPingPong() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-002");

        RealtimeMessage<String> pingMessage = RealtimeMessage.ping();
        handler.handleTextMessage(session, new TextMessage(JsonUtil.toJsonString(pingMessage)));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(1)).sendMessage(captor.capture());
        RealtimeMessage<?> pongResponse = JsonUtil.parseObject(captor.getValue().getPayload(), RealtimeMessage.class);
        assertThat(pongResponse).isNotNull();
        assertThat(pongResponse.getEvent()).isEqualTo(RealtimeMessage.EVENT_PONG);
        assertThat(pongResponse.getData()).isEqualTo("pong");
    }

    @Test
    void testNormalMessagePassThrough() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-003");

        RealtimeMessage<String> bizMessage = RealtimeMessage.of("chat:send", "hello");
        handler.handleTextMessage(session, new TextMessage(JsonUtil.toJsonString(bizMessage)));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void testLifecycleMethodsDoNotThrow() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-004");

        handler.afterConnectionEstablished(session);
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        handler.handlePongMessage(session, new PongMessage(ByteBuffer.wrap(new byte[]{0})));
        handler.handleTransportError(session, new RuntimeException("network glitch"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(handler.supportsPartialMessages()).isFalse();
    }

    @Test
    @DisplayName("WebSocket 会话自动注册、活跃时间刷新与上行 Action 分发")
    void testSessionManagerAutoRegistrationAndDispatch() throws Exception {
        RealtimeSessionManager sessionManager = new RealtimeSessionManager();
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        AtengWebSocketHandler managedHandler = new AtengWebSocketHandler(sessionManager, dispatcher);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-sess-100");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        SessionKey key = SessionKey.of("user-ws", "pc", "client-ws");
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, key);
        attrs.put(AtengWebSocketHandler.TENANT_ID_ATTR, 555L);
        when(session.getAttributes()).thenReturn(attrs);

        // 连接建立
        managedHandler.afterConnectionEstablished(session);
        RealtimeSession realtimeSession = sessionManager.getSession(key);
        assertThat(realtimeSession).isNotNull();
        assertThat(realtimeSession.getTenantId()).isEqualTo(555L);
        assertThat(sessionManager.getOnlineCount()).isEqualTo(1);

        // 接收 Pong 协议帧刷新活跃时间
        managedHandler.handlePongMessage(session, new PongMessage(ByteBuffer.wrap(new byte[]{0})));

        // 发送普通业务消息，触发 dispatcher.dispatch()
        managedHandler.handleTextMessage(session, new TextMessage("{\"event\":\"order:pay\",\"data\":\"100\"}"));
        verify(dispatcher, times(1)).dispatch(eq(realtimeSession), any(RealtimeMessage.class));

        // 连接断开
        managedHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(sessionManager.getSession(key)).isNull();
        assertThat(sessionManager.getOnlineCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("限流开启时单秒超限触发 429 丢弃警告，持续恶意刷量触发 4005 强制切断")
    void testRateLimitEscalationAndClosure() throws Exception {
        io.github.atengk.boot.realtime.properties.RealtimeProperties props = new io.github.atengk.boot.realtime.properties.RealtimeProperties();
        props.getWebsocket().getRateLimit().setEnabled(true);
        props.getWebsocket().getRateLimit().setMaxRequestsPerSecond(3);
        props.getWebsocket().getRateLimit().setConsecutiveViolationsBeforeClose(2);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(props);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        AtengWebSocketHandler managedHandler = new AtengWebSocketHandler(sessionManager, dispatcher, props);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("rate-sess-001");
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        SessionKey key = SessionKey.of("user-rate", "pc", "client-rate");
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, key);
        when(session.getAttributes()).thenReturn(attrs);

        // 1. 建立连接，注入确定性时钟限流器验证阶梯行为
        managedHandler.afterConnectionEstablished(session);
        RealtimeSession realtimeSession = sessionManager.getSession(key);
        assertThat(realtimeSession).isNotNull();
        java.util.concurrent.atomic.AtomicLong testClock = new java.util.concurrent.atomic.AtomicLong(1000000L);
        realtimeSession.setRateLimiter(new io.github.atengk.boot.realtime.ratelimit.SessionRateLimiter(3, 2, testClock::get));
        assertThat(realtimeSession.getRateLimiter()).isNotNull();

        // 2. 前 3 次请求放行（分发器被调用 3 次）
        TextMessage msg = new TextMessage("{\"event\":\"test:event\",\"data\":\"data\"}");
        for (int i = 0; i < 3; i++) {
            managedHandler.handleTextMessage(session, msg);
        }
        verify(dispatcher, times(3)).dispatch(eq(realtimeSession), any(RealtimeMessage.class));

        // 3. 第 4 次请求超限 (第 1 次违规) -> 丢弃且不下发给 dispatcher，发送 429 警告帧
        managedHandler.handleTextMessage(session, msg);
        verify(dispatcher, times(3)).dispatch(eq(realtimeSession), any(RealtimeMessage.class)); // 依然是 3 次
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("429");
        assertThat(realtimeSession.isOpen()).isTrue();

        // 4. 第 5 次请求超限 (第 2 次违规，达到阈值 2) -> 触发 4005 RATE_LIMIT_EXCEEDED 强制断开连接
        managedHandler.handleTextMessage(session, msg);
        verify(session, atLeastOnce()).close(eq(io.github.atengk.boot.realtime.constant.RealtimeCloseStatus.RATE_LIMIT_EXCEEDED));
    }
}
