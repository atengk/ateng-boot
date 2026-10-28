package io.github.atengk.boot.realtime.session;

import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.enums.EvictionPolicy;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 实时受管统一会话中心单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeSessionManager 统一会话中心测试")
class RealtimeSessionManagerTest {

    @Test
    @DisplayName("会话多维检索：按 SessionKey、用户、通道与租户索引")
    void testMultiDimensionalIndexAndRetrieval() {
        RealtimeSessionManager manager = new RealtimeSessionManager();

        // 注册用户 101 的 PC 与 Mobile 会话
        SessionKey key1 = SessionKey.of("user-101", "pc", "c-101-1");
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        RealtimeSession sess1 = RealtimeSession.ofWebSocket(key1, ws1, 1001L, null);

        SessionKey key2 = SessionKey.of("user-101", "mobile", "c-101-2");
        SseEmitter sse2 = mock(SseEmitter.class);
        RealtimeSession sess2 = RealtimeSession.ofSse(key2, sse2, 1001L, null);

        // 注册用户 102 的 Web 会话（不同租户）
        SessionKey key3 = SessionKey.of("user-102", "web", "c-102-1");
        WebSocketSession ws3 = mock(WebSocketSession.class);
        when(ws3.isOpen()).thenReturn(true);
        RealtimeSession sess3 = RealtimeSession.ofWebSocket(key3, ws3, 1002L, null);

        manager.registerSession(sess1);
        manager.registerSession(sess2);
        manager.registerSession(sess3);

        // 验证全局度量指标
        assertThat(manager.getOnlineCount()).isEqualTo(3);
        assertThat(manager.getOnlineUserCount()).isEqualTo(2);
        assertThat(manager.getOnlineCountByChannel(ChannelType.WEBSOCKET)).isEqualTo(2);
        assertThat(manager.getOnlineCountByChannel(ChannelType.SSE)).isEqualTo(1);

        // 精确 Key 检索
        assertThat(manager.getSession(key1)).isSameAs(sess1);
        assertThat(manager.getSession(key2)).isSameAs(sess2);
        assertThat(manager.getSession(key3)).isSameAs(sess3);
        assertThat(manager.containsSession(key1)).isTrue();

        // 按用户多端检索
        List<RealtimeSession> user101Sessions = manager.getSessionsByUser("user-101");
        assertThat(user101Sessions).hasSize(2).contains(sess1, sess2);

        // 按通道协议检索
        List<RealtimeSession> wsSessions = manager.getSessionsByChannel(ChannelType.WEBSOCKET);
        assertThat(wsSessions).hasSize(2).contains(sess1, sess3);

        List<RealtimeSession> sseSessions = manager.getSessionsByChannel(ChannelType.SSE);
        assertThat(sseSessions).hasSize(1).contains(sess2);

        // 按多租户检索
        List<RealtimeSession> tenant1001Sessions = manager.getSessionsByTenant(1001L);
        assertThat(tenant1001Sessions).hasSize(2).contains(sess1, sess2);

        List<RealtimeSession> tenant1002Sessions = manager.getSessionsByTenant(1002L);
        assertThat(tenant1002Sessions).hasSize(1).contains(sess3);

        // 移除 sess2
        RealtimeSession removed = manager.removeSession(key2);
        assertThat(removed).isSameAs(sess2);
        assertThat(manager.getOnlineCount()).isEqualTo(2);
        assertThat(manager.getOnlineCountByChannel(ChannelType.SSE)).isEqualTo(0);
        assertThat(manager.getSessionsByUser("user-101")).hasSize(1).contains(sess1);
    }

    @Test
    @DisplayName("配额超限治理：FIFO 淘汰最旧会话")
    void testSessionQuotaFifoEviction() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getSession().setMaxConnectionsPerUser(2);
        properties.getSession().setEvictionPolicy(EvictionPolicy.FIFO);

        RealtimeSessionManager manager = new RealtimeSessionManager(properties);

        SessionKey k1 = SessionKey.of("user-A", "tab1");
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        RealtimeSession s1 = RealtimeSession.ofWebSocket(k1, ws1);
        manager.registerSession(s1);

        Thread.sleep(10);
        SessionKey k2 = SessionKey.of("user-A", "tab2");
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);
        RealtimeSession s2 = RealtimeSession.ofWebSocket(k2, ws2);
        manager.registerSession(s2);

        assertThat(manager.getSessionsByUser("user-A")).hasSize(2);

        // 接入第 3 个会话，应 FIFO 踢掉最早建立的 s1
        Thread.sleep(10);
        SessionKey k3 = SessionKey.of("user-A", "tab3");
        WebSocketSession ws3 = mock(WebSocketSession.class);
        when(ws3.isOpen()).thenReturn(true);
        RealtimeSession s3 = RealtimeSession.ofWebSocket(k3, ws3);
        boolean registered = manager.registerSession(s3);

        assertThat(registered).isTrue();
        assertThat(manager.getSessionsByUser("user-A")).hasSize(2).contains(s2, s3);
        assertThat(manager.getSession(k1)).isNull();
        // 验证被淘汰的 s1 接收到了配额超限关闭码
        verify(ws1, times(1)).close(RealtimeCloseStatus.MAX_CONNECTIONS_EXCEEDED);
    }

    @Test
    @DisplayName("配额超限治理：REJECT 拒绝新连接")
    void testSessionQuotaRejectPolicy() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getSession().setMaxConnectionsPerUser(1);
        properties.getSession().setEvictionPolicy(EvictionPolicy.REJECT);

        RealtimeSessionManager manager = new RealtimeSessionManager(properties);

        SessionKey k1 = SessionKey.of("user-B", "device1");
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        RealtimeSession s1 = RealtimeSession.ofWebSocket(k1, ws1);
        manager.registerSession(s1);

        // 尝试接入第 2 个会话，应被 REJECT 拒绝
        SessionKey k2 = SessionKey.of("user-B", "device2");
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);
        RealtimeSession s2 = RealtimeSession.ofWebSocket(k2, ws2);
        boolean registered = manager.registerSession(s2);

        assertThat(registered).isFalse();
        assertThat(manager.getSessionsByUser("user-B")).hasSize(1).contains(s1);
        verify(ws2, times(1)).close(RealtimeCloseStatus.MAX_CONNECTIONS_EXCEEDED);
    }

    @Test
    @DisplayName("会话生命周期监听器回调触发")
    void testSessionLifecycleListener() throws Exception {
        RealtimeSessionManager manager = new RealtimeSessionManager();
        List<SessionKey> registeredKeys = new ArrayList<>();
        List<SessionKey> closedKeys = new ArrayList<>();

        manager.addListener(new RealtimeSessionListener() {
            @Override
            public void onSessionRegistered(RealtimeSession session) {
                registeredKeys.add(session.getSessionKey());
            }

            @Override
            public void onSessionClosed(RealtimeSession session) {
                closedKeys.add(session.getSessionKey());
            }
        });

        SessionKey k = SessionKey.of("u1", "c1");
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        RealtimeSession session = RealtimeSession.ofWebSocket(k, ws);

        manager.registerSession(session);
        assertThat(registeredKeys).containsExactly(k);

        manager.removeAndClose(k, RealtimeCloseStatus.USER_KICKED_OUT);
        assertThat(closedKeys).containsExactly(k);
        verify(ws, times(1)).close(RealtimeCloseStatus.USER_KICKED_OUT);
    }

    @Test
    @DisplayName("清理所有活跃会话")
    void testClearAllSessions() {
        RealtimeSessionManager manager = new RealtimeSessionManager();
        SessionKey k1 = SessionKey.of("u1", "c1");
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        manager.registerSession(RealtimeSession.ofWebSocket(k1, ws1));

        SessionKey k2 = SessionKey.of("u2", "c2");
        SseEmitter sse2 = mock(SseEmitter.class);
        manager.registerSession(RealtimeSession.ofSse(k2, sse2));

        assertThat(manager.getOnlineCount()).isEqualTo(2);

        manager.clear();

        assertThat(manager.getOnlineCount()).isEqualTo(0);
        assertThat(manager.getOnlineUserCount()).isEqualTo(0);
        assertThat(manager.getAllSessions()).isEmpty();
    }
}
