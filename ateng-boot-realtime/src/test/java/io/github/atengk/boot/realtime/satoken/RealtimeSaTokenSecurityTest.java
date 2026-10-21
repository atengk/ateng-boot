package io.github.atengk.boot.realtime.satoken;

import io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.cluster.RealtimeClusterPacket;
import io.github.atengk.boot.realtime.cluster.StandaloneRealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ClusterBroadcastType;
import io.github.atengk.boot.realtime.group.RealtimeGroupManager;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Sa-Token 账号下线/封禁与长连接跨集群切断联动测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeSaTokenListener 封禁注销跨集群即时切断测试")
class RealtimeSaTokenSecurityTest {

    private RealtimeSessionManager sessionManager;
    private RealtimeGroupManager groupManager;
    private RealtimeProperties properties;
    private RealtimeClusterBroadcaster clusterBroadcaster;
    private RealtimeTemplate template;
    private RealtimeSaTokenListener listener;

    @BeforeEach
    void setUp() {
        properties = new RealtimeProperties();
        sessionManager = new RealtimeSessionManager(properties);
        groupManager = new RealtimeGroupManager();
        clusterBroadcaster = mock(RealtimeClusterBroadcaster.class);
        when(clusterBroadcaster.isClusterEnabled()).thenReturn(true);
        when(clusterBroadcaster.getNodeId()).thenReturn("node-source");

        template = new RealtimeTemplate(sessionManager, groupManager, properties, clusterBroadcaster);
        listener = new RealtimeSaTokenListener(template);
    }

    @AfterEach
    void tearDown() {
        for (RealtimeSession s : sessionManager.getAllSessions()) {
            sessionManager.removeAndClose(s.getSessionKey(), CloseStatus.NORMAL);
        }
    }

    @Test
    @DisplayName("doLogout 登出事件联动: 切断本地活跃长连接 (4002 状态码) 并全网广播 KICKOUT 信令")
    void testDoLogoutKicksOutUserAndBroadcasts() throws Exception {
        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        SessionKey key = SessionKey.of("user-logout", "device-pc", "c1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key, mockWs));

        assertThat(sessionManager.containsSession(key)).isTrue();

        // 模拟 Sa-Token 触发登出事件
        listener.doLogout("login", "user-logout", "token-xxx");

        // 1. 验证本地会话已被移除
        assertThat(sessionManager.containsSession(key)).isFalse();
        // 2. 验证底层 WebSocket 连接被以 4002 (USER_KICKED_OUT) 状态码切断
        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(mockWs, times(1)).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(RealtimeCloseStatus.USER_KICKED_OUT.getCode());

        // 3. 验证触发全集群广播 KICKOUT 信令
        ArgumentCaptor<RealtimeClusterPacket> packetCaptor = ArgumentCaptor.forClass(RealtimeClusterPacket.class);
        verify(clusterBroadcaster, times(1)).broadcast(packetCaptor.capture());
        RealtimeClusterPacket packet = packetCaptor.getValue();
        assertThat(packet.getBroadcastType()).isEqualTo(ClusterBroadcastType.KICKOUT);
        assertThat(packet.getTargetKey()).isEqualTo("user-logout");
        assertThat(packet.getCloseStatusCode()).isEqualTo(RealtimeCloseStatus.USER_KICKED_OUT.getCode());
    }

    @Test
    @DisplayName("doKickout 踢人事件联动: 切断本地连接并全网广播")
    void testDoKickoutKicksOutUser() throws Exception {
        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        SessionKey key = SessionKey.of("user-kick", "device-app", "c2");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key, mockWs));

        listener.doKickout("login", "user-kick", "token-yyy");

        assertThat(sessionManager.containsSession(key)).isFalse();
        verify(mockWs, times(1)).close(any(CloseStatus.class));
        verify(clusterBroadcaster, times(1)).broadcast(any(RealtimeClusterPacket.class));
    }

    @Test
    @DisplayName("doDisable 封号事件联动: 账号被 Ban 立即全网切断持久长连接")
    void testDoDisableKicksOutUser() throws Exception {
        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        SessionKey key = SessionKey.of("user-ban", "client-1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key, mockWs));

        // 模拟封禁账号 86400 秒
        listener.doDisable("login", "user-ban", "all", 1, 86400L);

        assertThat(sessionManager.containsSession(key)).isFalse();
        verify(mockWs, times(1)).close(any(CloseStatus.class));
        verify(clusterBroadcaster, times(1)).broadcast(any(RealtimeClusterPacket.class));
    }

    @Test
    @DisplayName("doReplaced 顶人下线事件联动: 挤号时切断旧设备")
    void testDoReplacedKicksOutUser() throws Exception {
        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        SessionKey key = SessionKey.of("user-replaced", "client-1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key, mockWs));

        listener.doReplaced("login", "user-replaced", "token-old");

        assertThat(sessionManager.containsSession(key)).isFalse();
        verify(mockWs, times(1)).close(any(CloseStatus.class));
        verify(clusterBroadcaster, times(1)).broadcast(any(RealtimeClusterPacket.class));
    }

    @Test
    @DisplayName("doLogin 登录事件防御: 登录不执行任何切断操作")
    void testDoLoginDoesNothing() {
        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        SessionKey key = SessionKey.of("user-login", "client-1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key, mockWs));

        listener.doLogin("login", "user-login", "token-new", null);

        // 会话依然在线活跃
        assertThat(sessionManager.containsSession(key)).isTrue();
        verify(clusterBroadcaster, never()).broadcast(any());
    }

    @Test
    @DisplayName("远端节点消费 KICKOUT 信令: 远端节点收到广播包后精准切断本地匹配会话")
    void testRemoteKickoutPacketHandling() throws Exception {
        // 创建对端模拟节点
        RealtimeClusterBroadcaster remoteBroadcaster = mock(RealtimeClusterBroadcaster.class);
        when(remoteBroadcaster.getNodeId()).thenReturn("node-remote");
        when(remoteBroadcaster.isClusterEnabled()).thenReturn(true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Consumer<RealtimeClusterPacket>> consumerCaptor =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);

        RealtimeTemplate remoteTemplate = new RealtimeTemplate(sessionManager, groupManager, properties, remoteBroadcaster);
        verify(remoteBroadcaster).addConsumer(consumerCaptor.capture());
        java.util.function.Consumer<RealtimeClusterPacket> remoteConsumer = consumerCaptor.getValue();

        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        SessionKey key = SessionKey.of("user-remote-target", "client-1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key, mockWs));

        // 模拟接收到来自 node-source 的 KICKOUT 数据包
        RealtimeClusterPacket packet = RealtimeClusterPacket.ofKickout(
                "node-source", "user-remote-target", 4002, "Sa-Token Ban");

        // 触发远端广播消费回调
        remoteConsumer.accept(packet);

        // 验证远端节点上的目标会话被关闭
        assertThat(sessionManager.containsSession(key)).isFalse();
        ArgumentCaptor<CloseStatus> statusCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(mockWs, times(1)).close(statusCaptor.capture());
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(4002);
    }

    @Test
    @DisplayName("边界防御: null loginId 安全拦截不抛出异常")
    void testNullLoginIdSafety() {
        listener.doLogout("login", null, null);
        listener.doKickout("login", null, null);
        listener.doDisable("login", null, "service", 1, 100);
        listener.doReplaced("login", null, null);

        verify(clusterBroadcaster, never()).broadcast(any());
    }
}
