package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.dispatcher.RealtimeBinaryMessageListener;
import io.github.atengk.boot.realtime.dispatcher.RealtimeMessageDispatcher;
import io.github.atengk.boot.realtime.enums.EvictionPolicy;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.spi.RealtimeOfflineMessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WebSocket 建连自动补发离线消息单元测试
 *
 * @author Ateng
 * @since 2026-11-15
 */
@DisplayName("AtengWebSocketHandler 离线消息建连自动补发测试")
class AtengWebSocketOfflineAutoPushTest {

    @Test
    @DisplayName("默认关闭 auto-push-on-connect 时，建连不自动拉取离线消息（保持向后兼容）")
    void testAutoPushDisabledByDefault() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getOffline().setEnabled(true);
        // autoPushOnConnect 默认 false

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        RealtimeOfflineMessageProvider offlineProvider = mock(RealtimeOfflineMessageProvider.class);

        AtengWebSocketHandler handler = createHandler(properties, sessionManager, dispatcher, offlineProvider);
        WebSocketSession wsSession = mockAuthenticatedSession("ws-1", SessionKey.of("user-1", "ws-1"));

        handler.afterConnectionEstablished(wsSession);

        verify(offlineProvider, never()).pullAndClearOfflineMessages(any());
        verify(wsSession, never()).sendMessage(any());
    }

    @Test
    @DisplayName("开启 auto-push-on-connect 时，实名用户建连后按序自动补发并清空离线队列")
    void testAutoPushDeliversOfflineMessagesInOrderOnConnect() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getOffline().setEnabled(true);
        properties.getOffline().setAutoPushOnConnect(true);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        RealtimeOfflineMessageProvider offlineProvider = mock(RealtimeOfflineMessageProvider.class);

        RealtimeMessage<String> msg1 = RealtimeMessage.ofPersistent("notice", "first");
        RealtimeMessage<String> msg2 = RealtimeMessage.ofPersistent("notice", "second");
        when(offlineProvider.pullAndClearOfflineMessages("user-100")).thenReturn(List.of(msg1, msg2));

        AtengWebSocketHandler handler = createHandler(properties, sessionManager, dispatcher, offlineProvider);
        WebSocketSession wsSession = mockAuthenticatedSession("ws-100", SessionKey.of("user-100", "ws-100"));

        handler.afterConnectionEstablished(wsSession);

        assertThat(sessionManager.getSessionsByUser("user-100")).hasSize(1);
        var inOrder = inOrder(offlineProvider, wsSession);
        inOrder.verify(offlineProvider).pullAndClearOfflineMessages("user-100");
        inOrder.verify(wsSession).sendMessage(new TextMessage(JsonUtil.toJsonString(msg1)));
        inOrder.verify(wsSession).sendMessage(new TextMessage(JsonUtil.toJsonString(msg2)));
        verify(offlineProvider, never()).saveOfflineMessage(any(), any());
    }

    @Test
    @DisplayName("匿名用户建连即使开启 auto-push-on-connect 也跳过离线补发")
    void testAnonymousUserSkipsAutoPush() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getOffline().setEnabled(true);
        properties.getOffline().setAutoPushOnConnect(true);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        RealtimeOfflineMessageProvider offlineProvider = mock(RealtimeOfflineMessageProvider.class);

        AtengWebSocketHandler handler = createHandler(properties, sessionManager, dispatcher, offlineProvider);
        WebSocketSession wsSession = mockAuthenticatedSession("ws-anon", SessionKey.anonymous("ws-anon"));

        handler.afterConnectionEstablished(wsSession);

        verify(offlineProvider, never()).pullAndClearOfflineMessages(any());
        verify(wsSession, never()).sendMessage(any());
    }

    @Test
    @DisplayName("补发中途发送失败时，将该条及后续全部未投递消息按序回存以保证零丢消息")
    void testMidPushFailureResavesUndeliveredMessages() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getOffline().setEnabled(true);
        properties.getOffline().setAutoPushOnConnect(true);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        RealtimeOfflineMessageProvider offlineProvider = mock(RealtimeOfflineMessageProvider.class);

        RealtimeMessage<String> msg1 = RealtimeMessage.ofPersistent("notice", "m1");
        RealtimeMessage<String> msg2 = RealtimeMessage.ofPersistent("notice", "m2");
        RealtimeMessage<String> msg3 = RealtimeMessage.ofPersistent("notice", "m3");
        when(offlineProvider.pullAndClearOfflineMessages("user-err")).thenReturn(List.of(msg1, msg2, msg3));

        AtengWebSocketHandler handler = createHandler(properties, sessionManager, dispatcher, offlineProvider);
        WebSocketSession wsSession = mockAuthenticatedSession("ws-err", SessionKey.of("user-err", "ws-err"));
        doNothing()
                .doThrow(new IOException("Connection reset"))
                .when(wsSession).sendMessage(any(TextMessage.class));

        handler.afterConnectionEstablished(wsSession);

        verify(wsSession, times(2)).sendMessage(any(TextMessage.class));
        var inOrder = inOrder(offlineProvider);
        inOrder.verify(offlineProvider).pullAndClearOfflineMessages("user-err");
        inOrder.verify(offlineProvider).saveOfflineMessage("user-err", msg2);
        inOrder.verify(offlineProvider).saveOfflineMessage("user-err", msg3);
        verify(offlineProvider, never()).saveOfflineMessage("user-err", msg1);
    }

    @Test
    @DisplayName("offline.enabled=false 时即使开启 auto-push-on-connect 也不触发补发")
    void testOfflineDisabledSkipsAutoPushEvenWhenAutoPushEnabled() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getOffline().setEnabled(false);
        properties.getOffline().setAutoPushOnConnect(true);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        RealtimeOfflineMessageProvider offlineProvider = mock(RealtimeOfflineMessageProvider.class);

        AtengWebSocketHandler handler = createHandler(properties, sessionManager, dispatcher, offlineProvider);
        WebSocketSession wsSession = mockAuthenticatedSession("ws-off", SessionKey.of("user-off", "ws-off"));

        handler.afterConnectionEstablished(wsSession);

        assertThat(sessionManager.getSessionsByUser("user-off")).hasSize(1);
        verify(offlineProvider, never()).pullAndClearOfflineMessages(any());
    }

    @Test
    @DisplayName("会话被 REJECT 策略拒绝注册时，严禁触发离线消息拉取与清空")
    void testRejectedSessionSkipsAutoPush() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getOffline().setEnabled(true);
        properties.getOffline().setAutoPushOnConnect(true);
        properties.getSession().setMaxConnectionsPerUser(1);
        properties.getSession().setEvictionPolicy(EvictionPolicy.REJECT);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        RealtimeOfflineMessageProvider offlineProvider = mock(RealtimeOfflineMessageProvider.class);
        when(offlineProvider.pullAndClearOfflineMessages("user-reject")).thenReturn(List.of());

        AtengWebSocketHandler handler = createHandler(properties, sessionManager, dispatcher, offlineProvider);
        WebSocketSession first = mockAuthenticatedSession("ws-1", SessionKey.of("user-reject", "ws-1"));
        WebSocketSession second = mockAuthenticatedSession("ws-2", SessionKey.of("user-reject", "ws-2"));

        handler.afterConnectionEstablished(first);
        verify(offlineProvider, times(1)).pullAndClearOfflineMessages("user-reject");

        // 第二条连接触发 REJECT，不得再次拉取或清空离线消息
        handler.afterConnectionEstablished(second);
        verify(offlineProvider, times(1)).pullAndClearOfflineMessages("user-reject");
        verify(second).close(any());
    }

    @Test
    @DisplayName("拉取离线消息抛出异常时降级告警且不阻断 WebSocket 建连")
    void testPullFailureDoesNotBreakConnectionEstablishment() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getOffline().setEnabled(true);
        properties.getOffline().setAutoPushOnConnect(true);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeMessageDispatcher dispatcher = mock(RealtimeMessageDispatcher.class);
        RealtimeOfflineMessageProvider offlineProvider = mock(RealtimeOfflineMessageProvider.class);
        when(offlineProvider.pullAndClearOfflineMessages("user-boom"))
                .thenThrow(new RuntimeException("Redis timeout"));

        AtengWebSocketHandler handler = createHandler(properties, sessionManager, dispatcher, offlineProvider);
        WebSocketSession wsSession = mockAuthenticatedSession("ws-boom", SessionKey.of("user-boom", "ws-boom"));

        handler.afterConnectionEstablished(wsSession);

        assertThat(sessionManager.getSessionsByUser("user-boom")).hasSize(1);
        verify(wsSession, never()).sendMessage(any());
    }

    private AtengWebSocketHandler createHandler(RealtimeProperties properties,
                                                RealtimeSessionManager sessionManager,
                                                RealtimeMessageDispatcher dispatcher,
                                                RealtimeOfflineMessageProvider offlineProvider) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (offlineProvider != null) {
            beanFactory.addBean("offlineMessageProvider", offlineProvider);
        }
        ObjectProvider<RealtimeBinaryMessageListener> binaryProvider =
                beanFactory.getBeanProvider(RealtimeBinaryMessageListener.class);
        ObjectProvider<RealtimeOfflineMessageProvider> offlineBeanProvider =
                beanFactory.getBeanProvider(RealtimeOfflineMessageProvider.class);
        return new AtengWebSocketHandler(sessionManager, dispatcher, properties, binaryProvider, offlineBeanProvider);
    }

    private WebSocketSession mockAuthenticatedSession(String id, SessionKey sessionKey) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new ConcurrentHashMap<>();
        attrs.put(AtengWebSocketHandler.SESSION_KEY_ATTR, sessionKey);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }
}
