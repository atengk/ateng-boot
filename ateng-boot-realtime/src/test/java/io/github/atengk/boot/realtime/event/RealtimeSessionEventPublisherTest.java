package io.github.atengk.boot.realtime.event;

import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 实时会话生命周期应用事件发布测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeSessionEventPublisher 生命周期事件发布测试")
class RealtimeSessionEventPublisherTest {

    @Test
    @DisplayName("会话建立与断开事件发布及属性完整性验证")
    void testEventPublishing() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        RealtimeSessionEventPublisher publisher = new RealtimeSessionEventPublisher(eventPublisher);

        SessionKey key = SessionKey.of("user-event", "pc", "client-event");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession session = RealtimeSession.ofWebSocket(key, ws, 888L, null);

        // 1. 会话建立注册事件
        publisher.onSessionRegistered(session);

        ArgumentCaptor<RealtimeConnectedEvent> connCaptor = ArgumentCaptor.forClass(RealtimeConnectedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(connCaptor.capture());
        RealtimeConnectedEvent connEvent = connCaptor.getValue();
        assertThat(connEvent.getSession()).isSameAs(session);
        assertThat(connEvent.getSessionKey()).isEqualTo(key);
        assertThat(connEvent.getUserId()).isEqualTo("user-event");
        assertThat(connEvent.getChannelType()).isEqualTo(ChannelType.WEBSOCKET);
        assertThat(connEvent.getTenantId()).isEqualTo(888L);

        // 2. 会话断开注销事件
        publisher.onSessionClosed(session, RealtimeCloseStatus.USER_KICKED_OUT);

        ArgumentCaptor<RealtimeDisconnectedEvent> disconnCaptor = ArgumentCaptor.forClass(RealtimeDisconnectedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(disconnCaptor.capture());
        RealtimeDisconnectedEvent disconnEvent = disconnCaptor.getValue();
        assertThat(disconnEvent.getSession()).isSameAs(session);
        assertThat(disconnEvent.getSessionKey()).isEqualTo(key);
        assertThat(disconnEvent.getUserId()).isEqualTo("user-event");
        assertThat(disconnEvent.getChannelType()).isEqualTo(ChannelType.WEBSOCKET);
        assertThat(disconnEvent.getTenantId()).isEqualTo(888L);
        assertThat(disconnEvent.getCloseStatus()).isEqualTo(RealtimeCloseStatus.USER_KICKED_OUT);
    }
}
