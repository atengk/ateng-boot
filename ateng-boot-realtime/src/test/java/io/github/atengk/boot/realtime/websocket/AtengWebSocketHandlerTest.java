package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 核心原生 WebSocket 处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
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

        // 非 ping 消息在 Ticket 02 阶段不回复直接放行
        verify(session, never()).sendMessage(any());
    }

    @Test
    void testLifecycleMethodsDoNotThrow() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-004");

        handler.afterConnectionEstablished(session);
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        handler.handleTransportError(session, new RuntimeException("network glitch"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(handler.supportsPartialMessages()).isFalse();
    }
}
