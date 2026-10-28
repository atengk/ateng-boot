package io.github.atengk.boot.realtime.session;

import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 实时受管统一会话实体单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeSession 统一会话实体测试")
class RealtimeSessionTest {

    @Test
    @DisplayName("WebSocket 会话正常包装、发送与属性读写")
    void testWebSocketSessionLifecycle() throws Exception {
        SessionKey sessionKey = SessionKey.of("user-100", "web", "client-100");
        WebSocketSession rawSession = mock(WebSocketSession.class);
        when(rawSession.isOpen()).thenReturn(true);

        Map<String, Object> initialAttrs = new HashMap<>();
        initialAttrs.put("role", "admin");

        RealtimeSession session = RealtimeSession.ofWebSocket(sessionKey, rawSession, 888L, initialAttrs);

        assertThat(session.getSessionKey()).isEqualTo(sessionKey);
        assertThat(session.getUserId()).isEqualTo("user-100");
        assertThat(session.getDeviceType()).isEqualTo("web");
        assertThat(session.getClientId()).isEqualTo("client-100");
        assertThat(session.getChannelType()).isEqualTo(ChannelType.WEBSOCKET);
        assertThat(session.getTenantId()).isEqualTo(888L);
        assertThat(session.isOpen()).isTrue();
        assertThat(session.getAttribute("role")).isEqualTo("admin");

        // 动态修改属性
        session.setAttribute("ip", "127.0.0.1");
        assertThat(session.getAttribute("ip")).isEqualTo("127.0.0.1");
        session.removeAttribute("ip");
        assertThat(session.getAttribute("ip")).isNull();

        // 发送消息
        RealtimeMessage<String> msg = RealtimeMessage.of("notice", "hello");
        session.sendMessage(msg);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(rawSession, times(1)).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"event\":\"notice\"").contains("\"data\":\"hello\"");

        // 关闭会话
        session.close(RealtimeCloseStatus.NORMAL);
        assertThat(session.isOpen()).isFalse();
        verify(rawSession, times(1)).close(RealtimeCloseStatus.NORMAL);

        // 已关闭会话再次发送抛出 IOException
        assertThatThrownBy(() -> session.sendMessage(msg))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Session is closed");
    }

    @Test
    @DisplayName("SSE 会话正常包装、发送与关闭")
    void testSseSessionLifecycle() throws Exception {
        SessionKey sessionKey = SessionKey.of("user-200", "pc", "client-200");
        SseEmitter emitter = mock(SseEmitter.class);

        RealtimeSession session = RealtimeSession.ofSse(sessionKey, emitter, 999L, null);

        assertThat(session.getChannelType()).isEqualTo(ChannelType.SSE);
        assertThat(session.isOpen()).isTrue();

        RealtimeMessage<String> msg = RealtimeMessage.of("chat", "streaming payload");
        session.sendMessage(msg);
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));

        // 关闭会话
        session.close(CloseStatus.NORMAL);
        assertThat(session.isOpen()).isFalse();
        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("会话最后活跃时间刷新")
    void testUpdateActiveTime() throws InterruptedException {
        SessionKey sessionKey = SessionKey.of("u1", "c1");
        WebSocketSession rawSession = mock(WebSocketSession.class);
        RealtimeSession session = RealtimeSession.ofWebSocket(sessionKey, rawSession);

        long t1 = session.getLastActiveTime();
        Thread.sleep(5);
        session.updateActiveTime();
        long t2 = session.getLastActiveTime();

        assertThat(t2).isGreaterThan(t1);
    }
}
