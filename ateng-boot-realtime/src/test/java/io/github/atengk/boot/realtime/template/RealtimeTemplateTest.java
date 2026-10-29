package io.github.atengk.boot.realtime.template;

import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.group.RealtimeGroupManager;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RealtimeTemplate 统一消息推送门面测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeTemplate 统一消息推送门面测试")
class RealtimeTemplateTest {

    private RealtimeSessionManager sessionManager;
    private RealtimeGroupManager groupManager;
    private RealtimeProperties properties;
    private RealtimeTemplate template;

    @BeforeEach
    void setUp() {
        properties = new RealtimeProperties();
        groupManager = new RealtimeGroupManager();
        sessionManager = new RealtimeSessionManager(properties);
        sessionManager.addListener(groupManager);
        template = new RealtimeTemplate(sessionManager, groupManager, properties);
    }

    @Test
    @DisplayName("单播测试: sendToSession 精确投递与不存在会话保护")
    void testSendToSession() throws Exception {
        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);

        SessionKey key1 = SessionKey.of("user-01", "default", "client-1");
        RealtimeSession session = RealtimeSession.ofWebSocket(key1, mockWs);
        sessionManager.registerSession(session);

        RealtimeMessage<String> msg = RealtimeMessage.of("greet", "hello user1");

        boolean success = template.sendToSession(key1, msg);
        assertThat(success).isTrue();
        verify(mockWs, times(1)).sendMessage(any(TextMessage.class));

        // 向不存在会话发送保底返回 false
        boolean notFound = template.sendToSession(SessionKey.of("user-none", "client-none"), msg);
        assertThat(notFound).isFalse();
    }

    @Test
    @DisplayName("用户多端广播: sendToUser 向名下全部会话广播下发")
    void testSendToUser() throws Exception {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);

        SessionKey key1 = SessionKey.of("user-02", "pc", "c1");
        SessionKey key2 = SessionKey.of("user-02", "mobile", "c2");

        sessionManager.registerSession(RealtimeSession.ofWebSocket(key1, ws1, 100L, null));
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key2, ws2, 200L, null));

        RealtimeMessage<String> msg = RealtimeMessage.of("alert", "broadcast to user");

        int sentAll = template.sendToUser("user-02", msg);
        assertThat(sentAll).isEqualTo(2);

        // 指定租户发送
        int sentTenant = template.sendToUser("user-02", 100L, msg);
        assertThat(sentTenant).isEqualTo(1);
    }

    @Test
    @DisplayName("协议定向推流: sendViaWebSocket 与 sendViaSse 仅在协议匹配时发送")
    void testProtocolDirectedSend() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        SseEmitter sse = mock(SseEmitter.class);

        SessionKey wsKey = SessionKey.of("user-03", "default", "ws-client");
        SessionKey sseKey = SessionKey.of("user-03", "default", "sse-client");

        sessionManager.registerSession(RealtimeSession.ofWebSocket(wsKey, ws));
        sessionManager.registerSession(RealtimeSession.ofSse(sseKey, sse));

        RealtimeMessage<String> msg = RealtimeMessage.of("ping", "protocol check");

        // 对 ws 会话执行 sendViaWebSocket 应成功，执行 sendViaSse 应返回 false
        assertThat(template.sendViaWebSocket(wsKey, msg)).isTrue();
        assertThat(template.sendViaSse(wsKey, msg)).isFalse();

        // 对 sse 会话执行 sendViaSse 应成功，执行 sendViaWebSocket 应返回 false
        assertThat(template.sendViaSse(sseKey, msg)).isTrue();
        assertThat(template.sendViaWebSocket(sseKey, msg)).isFalse();

        // 按用户定向发送
        assertThat(template.sendToUserViaWebSocket("user-03", msg)).isEqualTo(1);
        assertThat(template.sendToUserViaSse("user-03", msg)).isEqualTo(1);
    }

    @Test
    @DisplayName("组播协同测试: joinGroup, sendToGroup, leaveGroup")
    void testGroupMulticast() throws Exception {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);

        SessionKey key1 = SessionKey.of("user-10", "c1");
        SessionKey key2 = SessionKey.of("user-20", "c2");

        sessionManager.registerSession(RealtimeSession.ofWebSocket(key1, ws1));
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key2, ws2));

        template.joinGroup("room-chat", key1);
        template.joinGroup("room-chat", key2);

        assertThat(template.getGroupMemberCount("room-chat")).isEqualTo(2);
        Set<SessionKey> members = template.getGroupSessions("room-chat");
        assertThat(members).containsExactlyInAnyOrder(key1, key2);

        RealtimeMessage<String> msg = RealtimeMessage.of("chat", "room message");
        int sent = template.sendToGroup("room-chat", msg);
        assertThat(sent).isEqualTo(2);

        // 移出 key1
        template.leaveGroup("room-chat", key1);
        assertThat(template.getGroupMemberCount("room-chat")).isEqualTo(1);

        // 按 userId 批量加组
        int joined = template.joinGroup("room-vip", "user-10");
        assertThat(joined).isEqualTo(1);
        assertThat(template.getGroupMemberCount("room-vip")).isEqualTo(1);
    }

    @Test
    @DisplayName("广播测试: sendToTenant 与 sendToAll 全服下发")
    void testBroadcast() throws Exception {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);

        sessionManager.registerSession(RealtimeSession.ofWebSocket(SessionKey.of("u1", "c1"), ws1, 1000L, null));
        sessionManager.registerSession(RealtimeSession.ofWebSocket(SessionKey.of("u2", "c2"), ws2, 2000L, null));

        RealtimeMessage<String> msg = RealtimeMessage.of("notice", "broadcast notice");

        int sentTenant = template.sendToTenant(1000L, msg);
        assertThat(sentTenant).isEqualTo(1);

        int sentAll = template.sendToAll(msg);
        assertThat(sentAll).isEqualTo(2);
    }

    @Test
    @DisplayName("会话驱逐与下线: kickoutSession 与 kickoutUser 踢除并关闭连接")
    void testKickout() throws Exception {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);

        SessionKey key1 = SessionKey.of("user-kick", "pc", "c1");
        SessionKey key2 = SessionKey.of("user-kick", "mobile", "c2");

        sessionManager.registerSession(RealtimeSession.ofWebSocket(key1, ws1));
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key2, ws2));

        assertThat(template.isUserOnline("user-kick")).isTrue();
        assertThat(template.getOnlineCount()).isEqualTo(2);

        // 踢除单会话
        boolean kicked = template.kickoutSession(key1);
        assertThat(kicked).isTrue();
        assertThat(template.getOnlineCount()).isEqualTo(1);
        verify(ws1, times(1)).close(RealtimeCloseStatus.USER_KICKED_OUT);

        // 踢除用户所有剩余会话
        int kickedUsers = template.kickoutUser("user-kick");
        assertThat(kickedUsers).isEqualTo(1);
        assertThat(template.isUserOnline("user-kick")).isFalse();
        assertThat(template.getOnlineCount()).isZero();
    }

    @Test
    @DisplayName("度量统计测试: getOnlineCount 与各维度在线判断")
    void testMetrics() {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        SseEmitter sse = mock(SseEmitter.class);

        sessionManager.registerSession(RealtimeSession.ofWebSocket(SessionKey.of("uA", "c1"), ws));
        sessionManager.registerSession(RealtimeSession.ofSse(SessionKey.of("uB", "c2"), sse));

        assertThat(template.getOnlineCount()).isEqualTo(2);
        assertThat(template.getOnlineUserCount()).isEqualTo(2);
        assertThat(template.getOnlineCountByChannel(ChannelType.WEBSOCKET)).isEqualTo(1);
        assertThat(template.getOnlineCountByChannel(ChannelType.SSE)).isEqualTo(1);
        assertThat(template.isSessionOnline(SessionKey.of("uA", "c1"))).isTrue();
        assertThat(template.isSessionOnline(SessionKey.of("uNone", "c0"))).isFalse();
    }

    @Test
    @DisplayName("健壮性测试: 发生 IOException 时自动排空死链并返回 false")
    void testDefensiveExceptionHandling() throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        doThrow(new IOException("Broken pipe")).when(ws).sendMessage(any(TextMessage.class));

        SessionKey key = SessionKey.of("user-err", "c1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(key, ws));

        boolean sent = template.sendToSession(key, RealtimeMessage.of("test", "data"));
        assertThat(sent).isFalse();

        // 验证由于发生异常，该会话已被安全自动排空移除
        assertThat(template.isSessionOnline(key)).isFalse();
    }

    @Test
    @DisplayName("解散群组测试: dismissGroup 移出成员并向其下发 group_dismissed 事件通知")
    void testDismissGroupAndEventEmission() throws Exception {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);

        SessionKey k1 = SessionKey.of("user-dis-1", "c1");
        SessionKey k2 = SessionKey.of("user-dis-2", "c2");

        sessionManager.registerSession(RealtimeSession.ofWebSocket(k1, ws1));
        sessionManager.registerSession(RealtimeSession.ofWebSocket(k2, ws2));

        template.joinGroup("room-to-dismiss", k1);
        template.joinGroup("room-to-dismiss", k2);

        assertThat(template.getClusterGroupMemberCount("room-to-dismiss")).isEqualTo(2);
        assertThat(template.getClusterGroupMembers("room-to-dismiss")).containsExactlyInAnyOrder(k1, k2);

        int dismissedCount = template.dismissGroup("room-to-dismiss");
        assertThat(dismissedCount).isEqualTo(2);

        // 验证群组已被移出
        assertThat(template.getGroupMemberCount("room-to-dismiss")).isZero();
        assertThat(template.getGroupSessions("room-to-dismiss")).isEmpty();

        // 验证客户端已收到 group_dismissed 消息
        verify(ws1, atLeastOnce()).sendMessage(argThat(msg ->
                ((TextMessage) msg).getPayload().contains("group_dismissed")));
        verify(ws2, atLeastOnce()).sendMessage(argThat(msg ->
                ((TextMessage) msg).getPayload().contains("group_dismissed")));
    }

    @Test
    @DisplayName("租户全量剔除: kickoutTenant 驱逐名下所有活跃会话")
    void testKickoutTenant() throws Exception {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        WebSocketSession ws2 = mock(WebSocketSession.class);
        when(ws2.isOpen()).thenReturn(true);

        SessionKey k1 = SessionKey.of("u1", "c1");
        SessionKey k2 = SessionKey.of("u2", "c2");

        sessionManager.registerSession(RealtimeSession.ofWebSocket(k1, ws1, 888L, null));
        sessionManager.registerSession(RealtimeSession.ofWebSocket(k2, ws2, 888L, null));

        assertThat(sessionManager.getOnlineCountByTenant(888L)).isEqualTo(2);

        int kicked = template.kickoutTenant(888L);
        assertThat(kicked).isEqualTo(2);
        assertThat(sessionManager.getOnlineCountByTenant(888L)).isZero();

        verify(ws1).close(RealtimeCloseStatus.USER_KICKED_OUT);
        verify(ws2).close(RealtimeCloseStatus.USER_KICKED_OUT);
    }

    @Test
    @DisplayName("远端集群广播信令调度测试: DISMISS_GROUP 与 KICKOUT_TENANT")
    void testDispatchRemoteDismissGroupAndKickoutTenant() throws Exception {
        io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster mockBroadcaster =
                mock(io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster.class);
        when(mockBroadcaster.isClusterEnabled()).thenReturn(true);
        when(mockBroadcaster.getNodeId()).thenReturn("node-remote-test");

        RealtimeTemplate clusterTemplate = new RealtimeTemplate(
                sessionManager, groupManager, properties, mockBroadcaster);

        // 捕获注册的消费者回调
        org.mockito.ArgumentCaptor<java.util.function.Consumer<io.github.atengk.boot.realtime.cluster.RealtimeClusterPacket>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(mockBroadcaster).addConsumer(captor.capture());
        java.util.function.Consumer<io.github.atengk.boot.realtime.cluster.RealtimeClusterPacket> consumer = captor.getValue();

        // 1. 模拟收到 DISMISS_GROUP 数据包
        WebSocketSession wsDismiss = mock(WebSocketSession.class);
        when(wsDismiss.isOpen()).thenReturn(true);
        SessionKey kDismiss = SessionKey.of("user-remote-dis", "c1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(kDismiss, wsDismiss));
        clusterTemplate.joinGroup("group-remote", kDismiss);

        io.github.atengk.boot.realtime.cluster.RealtimeClusterPacket dismissPacket =
                io.github.atengk.boot.realtime.cluster.RealtimeClusterPacket.ofDismissGroup("node-other", "group-remote");
        consumer.accept(dismissPacket);

        assertThat(groupManager.getGroupMemberCount("group-remote")).isZero();
        verify(wsDismiss, atLeastOnce()).sendMessage(argThat(msg ->
                ((TextMessage) msg).getPayload().contains("group_dismissed")));

        // 2. 模拟收到 KICKOUT_TENANT 数据包
        WebSocketSession wsTenant = mock(WebSocketSession.class);
        when(wsTenant.isOpen()).thenReturn(true);
        SessionKey kTenant = SessionKey.of("user-remote-tenant", "c2");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(kTenant, wsTenant, 999L, null));

        io.github.atengk.boot.realtime.cluster.RealtimeClusterPacket kickTenantPacket =
                io.github.atengk.boot.realtime.cluster.RealtimeClusterPacket.ofKickoutTenant("node-other", 999L, 4002, "Tenant disabled");
        consumer.accept(kickTenantPacket);

        assertThat(sessionManager.getOnlineCountByTenant(999L)).isZero();
        verify(wsTenant).close(any(org.springframework.web.socket.CloseStatus.class));
    }
}
