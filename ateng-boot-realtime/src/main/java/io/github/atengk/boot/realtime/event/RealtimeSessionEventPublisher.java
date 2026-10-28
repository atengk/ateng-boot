package io.github.atengk.boot.realtime.event;

import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.CloseStatus;

/**
 * 实时会话 Spring 原生应用事件发布器
 * <p>
 * 实现 RealtimeSessionListener 接口，在会话生命周期变更时向 Spring 事件体系发布事件。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@RequiredArgsConstructor
public class RealtimeSessionEventPublisher implements RealtimeSessionListener {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void onSessionRegistered(RealtimeSession session) {
        if (eventPublisher != null && session != null) {
            log.debug("[Realtime] 发布连接建立应用事件: sessionKey={}, channelType={}",
                    session.getSessionKey(), session.getChannelType());
            eventPublisher.publishEvent(new RealtimeConnectedEvent(this, session));
        }
    }

    @Override
    public void onSessionClosed(RealtimeSession session) {
        onSessionClosed(session, null);
    }

    @Override
    public void onSessionClosed(RealtimeSession session, CloseStatus status) {
        if (eventPublisher != null && session != null) {
            log.debug("[Realtime] 发布连接断开应用事件: sessionKey={}, channelType={}, status={}",
                    session.getSessionKey(), session.getChannelType(), status);
            eventPublisher.publishEvent(new RealtimeDisconnectedEvent(this, session, status));
        }
    }
}
