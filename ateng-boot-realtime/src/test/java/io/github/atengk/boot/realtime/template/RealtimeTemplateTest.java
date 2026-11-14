package io.github.atengk.boot.realtime.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.cluster.RealtimeClusterPacket;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.group.RealtimeGroupManager;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
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
    @DisplayName("二进制下行推送: sendBinary 向 WebSocket 会话发送二进制数据帧")
    void testSendBinary() throws Exception {
        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);

        SessionKey key = SessionKey.of("user-bin-send", "client-bin");
        RealtimeSession session = RealtimeSession.ofWebSocket(key, mockWs);
        sessionManager.registerSession(session);

        byte[] payload = new byte[]{10, 20, 30};
        boolean success = template.sendBinary(key, payload);
        assertThat(success).isTrue();
        verify(mockWs, times(1)).sendMessage(any(org.springframework.web.socket.BinaryMessage.class));

        // 向不存在会话发送保底返回 false
        assertThat(template.sendBinary(SessionKey.of("non-exist", "c"), payload)).isFalse();
        // 空入参防御
        assertThat(template.sendBinary(null, payload)).isFalse();
        assertThat(template.sendBinary(key, null)).isFalse();
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
        assertThat(template.getSessionsByTenant(888L)).hasSize(2);
        assertThat(template.getSessionsByTenant(null)).isEmpty();

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

    @Test
    @DisplayName("事务提交后推送测试: 活跃事务提交后才投递、冻结上下文并在回调后清理恢复")
    void testSendAfterCommitInActiveTransaction() throws Exception {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        SessionKey sessionKey = SessionKey.of("user-tx-1", "tab-1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(sessionKey, ws1, 100L, null));
        template.joinGroup("group-tx", sessionKey);

        MDC.put("traceId", "trace-tx-origin");
        TenantContextHolder.setTenantId("100");
        UserContextHolder.set("admin-1");

        TransactionSynchronizationManager.initSynchronization();

        RealtimeMessage<String> deferredMsg = RealtimeMessage.<String>builder()
                .event("order:paid")
                .data("order-001")
                .build();

        // 1. 注册各类 AfterCommit 投递：在事务未提交前严禁提前下发
        template.sendToSessionAfterCommit(sessionKey, deferredMsg);
        template.sendToSessionAfterCommit("user-tx-1", "tab-1", RealtimeMessage.of("session:pair", "ok"));
        template.sendToUserAfterCommit("user-tx-1", RealtimeMessage.of("user:msg", "hello"));
        template.sendToUserAfterCommit("user-tx-1", 100L, RealtimeMessage.of("user:tenant", "hello-t"));
        template.sendToGroupAfterCommit("group-tx", RealtimeMessage.of("group:msg", "hello-g"));
        template.sendToTenantAfterCommit(100L, RealtimeMessage.of("tenant:msg", "hello-100"));
        template.sendToAllAfterCommit(RealtimeMessage.of("all:msg", "hello-all"));

        verify(ws1, never()).sendMessage(any());

        // 验证注册期已预冻结 traceId 与 tenantId 到消息载荷
        assertThat(deferredMsg.getId()).isNotBlank();
        assertThat(deferredMsg.getTimestamp()).isNotNull();
        assertThat(deferredMsg.getTraceId()).isEqualTo("trace-tx-origin");
        assertThat(deferredMsg.getTenantId()).isEqualTo("100");

        // 模拟调用线程随后清空上下文（验证 afterCommit 执行时能恢复快照，执行完后再清理还原）
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(7);
        for (TransactionSynchronization sync : synchronizations) {
            sync.afterCommit();
        }

        // 所有 7 条消息应在提交后全部投递
        verify(ws1, times(7)).sendMessage(any(TextMessage.class));
        // 回调完成后当前线程上下文应被清理恢复，不可泄漏
        assertThat(MDC.get("traceId")).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(UserContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("事务回滚测试: 事务回滚时不触发 AfterCommit 推送")
    void testSendAfterCommitOnRollbackDoesNotSend() throws Exception {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        SessionKey sessionKey = SessionKey.of("user-tx-rb", "tab-1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(sessionKey, ws1));

        TransactionSynchronizationManager.initSynchronization();
        template.sendToSessionAfterCommit(sessionKey, RealtimeMessage.of("order:cancel", "rb"));

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(ws1, never()).sendMessage(any());
    }

    @Test
    @DisplayName("无事务同步上下文降级测试: sendAfterCommit 立即同步下发")
    void testSendAfterCommitWithoutActiveTransactionSendsImmediately() throws Exception {
        WebSocketSession ws1 = mock(WebSocketSession.class);
        when(ws1.isOpen()).thenReturn(true);
        SessionKey sessionKey = SessionKey.of("user-notx", "tab-1");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(sessionKey, ws1, 200L, null));
        template.joinGroup("group-notx", sessionKey);

        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        template.sendToSessionAfterCommit(sessionKey, RealtimeMessage.of("e1", "1"));
        template.sendToSessionAfterCommit("user-notx", "tab-1", RealtimeMessage.of("e2", "2"));
        template.sendToUserAfterCommit("user-notx", RealtimeMessage.of("e3", "3"));
        template.sendToUserAfterCommit("user-notx", 200L, RealtimeMessage.of("e4", "4"));
        template.sendToGroupAfterCommit("group-notx", RealtimeMessage.of("e5", "5"));
        template.sendToTenantAfterCommit(200L, RealtimeMessage.of("e6", "6"));
        template.sendToAllAfterCommit(RealtimeMessage.of("e7", "7"));

        verify(ws1, times(7)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("排除会话推送测试: 单机模式下 sendToUserExcept / sendToGroupExcept / sendToAllExcept")
    void testSendExceptStandalone() throws Exception {
        WebSocketSession wsUser1TabA = mock(WebSocketSession.class);
        WebSocketSession wsUser1TabB = mock(WebSocketSession.class);
        WebSocketSession wsUser2TabA = mock(WebSocketSession.class);
        when(wsUser1TabA.isOpen()).thenReturn(true);
        when(wsUser1TabB.isOpen()).thenReturn(true);
        when(wsUser2TabA.isOpen()).thenReturn(true);

        SessionKey k1A = SessionKey.of("u-ex-1", "tab-a");
        SessionKey k1B = SessionKey.of("u-ex-1", "tab-b");
        SessionKey k2A = SessionKey.of("u-ex-2", "tab-a");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(k1A, wsUser1TabA, 10L, null));
        sessionManager.registerSession(RealtimeSession.ofWebSocket(k1B, wsUser1TabB, 10L, null));
        sessionManager.registerSession(RealtimeSession.ofWebSocket(k2A, wsUser2TabA, 10L, null));
        template.joinGroup("group-ex", k1A);
        template.joinGroup("group-ex", k1B);
        template.joinGroup("group-ex", k2A);

        // 1. sendToUserExcept (varargs) 排除 k1A，仅投递 k1B
        int userSent = template.sendToUserExcept("u-ex-1", RealtimeMessage.of("user:sync", "tab-sync"), k1A);
        assertThat(userSent).isEqualTo(1);
        verify(wsUser1TabA, never()).sendMessage(any());
        verify(wsUser1TabB, times(1)).sendMessage(any(TextMessage.class));

        // 2. sendToUserExcept (tenantId + Collection) 排除 k1B，仅投递 k1A
        int userTenantSent = template.sendToUserExcept("u-ex-1", 10L, RealtimeMessage.of("user:tenant", "sync2"), List.of(k1B));
        assertThat(userTenantSent).isEqualTo(1);
        verify(wsUser1TabA, times(1)).sendMessage(any(TextMessage.class));

        // 3. sendToGroupExcept (varargs & Collection) 排除多个会话
        int groupSent = template.sendToGroupExcept("group-ex", RealtimeMessage.of("group:chat", "hello"), k1A, k2A);
        assertThat(groupSent).isEqualTo(1);
        verify(wsUser1TabB, times(2)).sendMessage(any(TextMessage.class));
        verify(wsUser2TabA, never()).sendMessage(any());

        // 4. sendToAllExcept (null 排除列表兼容与非空排除)
        int allWithNull = template.sendToAllExcept(RealtimeMessage.of("all:1", "a1"), (SessionKey[]) null);
        assertThat(allWithNull).isEqualTo(3);
        int allExcluded = template.sendToAllExcept(RealtimeMessage.of("all:2", "a2"), Set.of(k1A, k1B));
        assertThat(allExcluded).isEqualTo(1);
        verify(wsUser2TabA, times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("排除会话集群广播测试: Packet JSON 往返携带 excludeSessionKeys 且远端 dispatch 精准过滤")
    void testSendExceptClusterBroadcastAndRemoteDispatch() throws Exception {
        RealtimeClusterBroadcaster mockBroadcaster = mock(RealtimeClusterBroadcaster.class);
        when(mockBroadcaster.isClusterEnabled()).thenReturn(true);
        when(mockBroadcaster.getNodeId()).thenReturn("node-local");

        RealtimeTemplate clusterTemplate = new RealtimeTemplate(
                sessionManager, groupManager, properties, mockBroadcaster);

        org.mockito.ArgumentCaptor<java.util.function.Consumer<RealtimeClusterPacket>> consumerCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(mockBroadcaster).addConsumer(consumerCaptor.capture());
        java.util.function.Consumer<RealtimeClusterPacket> consumer = consumerCaptor.getValue();

        WebSocketSession wsExclude = mock(WebSocketSession.class);
        WebSocketSession wsReceive = mock(WebSocketSession.class);
        when(wsExclude.isOpen()).thenReturn(true);
        when(wsReceive.isOpen()).thenReturn(true);

        SessionKey kExclude = SessionKey.of("u-c-1", "tab-sender");
        SessionKey kReceive = SessionKey.of("u-c-1", "tab-other");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(kExclude, wsExclude, 20L, null));
        sessionManager.registerSession(RealtimeSession.ofWebSocket(kReceive, wsReceive, 20L, null));
        clusterTemplate.joinGroup("group-cluster-ex", kExclude);
        clusterTemplate.joinGroup("group-cluster-ex", kReceive);

        // 本地发起排除广播，验证发往广播器的 Packet 携带了 excludeSessionKeys
        clusterTemplate.sendToGroupExcept("group-cluster-ex", RealtimeMessage.of("g:msg", "hi"), kExclude);
        org.mockito.ArgumentCaptor<RealtimeClusterPacket> packetCaptor =
                org.mockito.ArgumentCaptor.forClass(RealtimeClusterPacket.class);
        verify(mockBroadcaster).broadcast(packetCaptor.capture());

        RealtimeClusterPacket sentPacket = packetCaptor.getValue();
        assertThat(sentPacket.getExcludeSessionKeys()).containsExactly(kExclude.format());

        // 模拟 JSON 序列化/反序列化往返后从远端节点消费 (sourceNodeId 改为另一节点)
        String json = JsonUtil.toJsonString(sentPacket);
        RealtimeClusterPacket remoteGroupPacket = JsonUtil.parseObject(json, RealtimeClusterPacket.class);
        remoteGroupPacket.setSourceNodeId("node-remote");
        consumer.accept(remoteGroupPacket);

        // 模拟 USER 与 ALL 远端包携带 excludeSessionKeys
        RealtimeClusterPacket remoteUserPacket = RealtimeClusterPacket.ofUser(
                "node-remote", "u-c-1", 20L, RealtimeMessage.of("u:msg", "u"), List.of(kExclude));
        consumer.accept(JsonUtil.parseObject(JsonUtil.toJsonString(remoteUserPacket), RealtimeClusterPacket.class));

        RealtimeClusterPacket remoteAllPacket = RealtimeClusterPacket.ofAll(
                "node-remote", RealtimeMessage.of("a:msg", "a"), List.of(kExclude));
        consumer.accept(JsonUtil.parseObject(JsonUtil.toJsonString(remoteAllPacket), RealtimeClusterPacket.class));

        // 被排除会话 0 次收到（本地 1 次广播期间已被排除，远端 3 次也全部排除），未排除会话共 4 次收到
        verify(wsExclude, never()).sendMessage(any());
        verify(wsReceive, times(4)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("排除会话多租户离线与 AfterCommit 空参数卫语句边界测试")
    void testSendExceptTenantOfflineBoundaryAndAfterCommitNullGuards() {
        io.github.atengk.boot.realtime.spi.RealtimeOfflineMessageProvider mockOfflineProvider =
                Mockito.mock(io.github.atengk.boot.realtime.spi.RealtimeOfflineMessageProvider.class);
        RealtimeTemplate templateWithOffline = new RealtimeTemplate(
                sessionManager,
                groupManager,
                properties,
                new io.github.atengk.boot.realtime.cluster.StandaloneRealtimeClusterBroadcaster(),
                mockOfflineProvider
        );

        WebSocketSession wsTenant10 = mock(WebSocketSession.class);
        when(wsTenant10.isOpen()).thenReturn(true);
        SessionKey kTenant10 = SessionKey.of("u-multi-tenant", "tab-10");
        sessionManager.registerSession(RealtimeSession.ofWebSocket(kTenant10, wsTenant10, 10L, null));

        // 1. 同租户下仅排除发送端自身在线会话时，用户在该租户内仍视为在线，不应触发离线暂存
        RealtimeMessage<String> sameTenantMsg = RealtimeMessage.ofPersistent("sync:tab", "p1");
        templateWithOffline.sendToUserExcept("u-multi-tenant", 10L, sameTenantMsg, kTenant10);
        verify(mockOfflineProvider, never()).saveOfflineMessage(any(), any());

        // 2. 向用户未登录的租户 20 推送且持久化，应视为在租户 20 离线并触发离线暂存
        RealtimeMessage<String> otherTenantMsg = RealtimeMessage.ofPersistent("notice:tenant20", "p2");
        templateWithOffline.sendToUserExcept("u-multi-tenant", 20L, otherTenantMsg, kTenant10);
        verify(mockOfflineProvider, times(1)).saveOfflineMessage("u-multi-tenant", otherTenantMsg);

        // 3. send*AfterCommit 在空参数或空白 groupId 下即使开启事务同步也不应注册回调
        TransactionSynchronizationManager.initSynchronization();
        template.sendToSessionAfterCommit((SessionKey) null, RealtimeMessage.of("e", "d"));
        template.sendToSessionAfterCommit("u1", null, RealtimeMessage.of("e", "d"));
        template.sendToUserAfterCommit(null, RealtimeMessage.of("e", "d"));
        template.sendToGroupAfterCommit("   ", RealtimeMessage.of("e", "d"));
        template.sendToTenantAfterCommit(null, RealtimeMessage.of("e", "d"));
        template.sendToAllAfterCommit(null);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }
}
