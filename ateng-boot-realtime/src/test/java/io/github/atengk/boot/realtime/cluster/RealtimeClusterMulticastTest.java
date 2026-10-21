package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.group.RealtimeGroupManager;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 跨节点分布式单播、组播与踢人协同测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("跨节点分布式协同集成测试")
class RealtimeClusterMulticastTest {

    /**
     * 内存模拟互联总线广播器（模拟两个节点共享 Redis Topic 通道）
     */
    static class MockSharedBusBroadcaster implements RealtimeClusterBroadcaster {
        private final String nodeId;
        private final List<MockSharedBusBroadcaster> clusterNodes;
        private final List<Consumer<RealtimeClusterPacket>> consumers = new CopyOnWriteArrayList<>();

        public MockSharedBusBroadcaster(String nodeId, List<MockSharedBusBroadcaster> clusterNodes) {
            this.nodeId = nodeId;
            this.clusterNodes = clusterNodes;
            this.clusterNodes.add(this);
        }

        @Override
        public void broadcast(RealtimeClusterPacket packet) {
            if (packet.getSourceNodeId() == null) {
                packet.setSourceNodeId(this.nodeId);
            }
            // 广播至集群所有节点
            for (MockSharedBusBroadcaster node : clusterNodes) {
                node.receiveFromBus(packet);
            }
        }

        public void receiveFromBus(RealtimeClusterPacket packet) {
            // 防反射自发自收过滤
            if (this.nodeId.equals(packet.getSourceNodeId())) {
                return;
            }
            for (Consumer<RealtimeClusterPacket> consumer : consumers) {
                consumer.accept(packet);
            }
        }

        @Override
        public String getNodeId() {
            return this.nodeId;
        }

        @Override
        public boolean isClusterEnabled() {
            return true;
        }

        @Override
        public void addConsumer(Consumer<RealtimeClusterPacket> consumer) {
            this.consumers.add(consumer);
        }
    }

    private RealtimeSession createMockSession(String userId, String clientId, Long tenantId, List<RealtimeMessage<?>> receivedSink) throws IOException {
        SessionKey key = SessionKey.of(userId, "web", clientId);
        WebSocketSession wsSession = mock(WebSocketSession.class);
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getId()).thenReturn("ws-" + clientId);

        doAnswer(invocation -> {
            TextMessage textMsg = invocation.getArgument(0);
            RealtimeMessage<?> msg = JsonUtil.parseObject(textMsg.getPayload(), RealtimeMessage.class);
            receivedSink.add(msg);
            return null;
        }).when(wsSession).sendMessage(any(TextMessage.class));

        return RealtimeSession.ofWebSocket(key, wsSession, tenantId, null);
    }

    @Test
    @DisplayName("跨节点用户单播：NodeA 发送，NodeB 上的目标用户成功接收")
    void testCrossNodeUserUnicast() throws Exception {
        List<MockSharedBusBroadcaster> bus = new ArrayList<>();
        RealtimeProperties props = new RealtimeProperties();

        // 构造 Node A
        RealtimeSessionManager smA = new RealtimeSessionManager(props);
        RealtimeGroupManager gmA = new RealtimeGroupManager();
        MockSharedBusBroadcaster bA = new MockSharedBusBroadcaster("node-A", bus);
        RealtimeTemplate templateA = new RealtimeTemplate(smA, gmA, props, bA);

        // 构造 Node B
        RealtimeSessionManager smB = new RealtimeSessionManager(props);
        RealtimeGroupManager gmB = new RealtimeGroupManager();
        MockSharedBusBroadcaster bB = new MockSharedBusBroadcaster("node-B", bus);
        RealtimeTemplate templateB = new RealtimeTemplate(smB, gmB, props, bB);

        // Node B 上连接用户 user-88
        List<RealtimeMessage<?>> nodeBUserMessages = new ArrayList<>();
        RealtimeSession sessionUser88 = createMockSession("user-88", "c1", 1L, nodeBUserMessages);
        smB.registerSession(sessionUser88);

        // Node A 上发起向 user-88 推送
        RealtimeMessage<String> message = RealtimeMessage.of("greet", "hello cross node");
        int localCount = templateA.sendToUser("user-88", message);

        // 验证：Node A 本地无会话，localCount 为 0；Node B 上的 user-88 成功接收该消息
        assertThat(localCount).isEqualTo(0);
        assertThat(nodeBUserMessages).hasSize(1);
        assertThat(nodeBUserMessages.get(0).getData()).isEqualTo("hello cross node");
    }

    @Test
    @DisplayName("去中心化跨节点组播：NodeA 与 NodeB 用户各在本地加入同一房间，NodeA 发送全员收到")
    void testCrossNodeGroupMulticast() throws Exception {
        List<MockSharedBusBroadcaster> bus = new ArrayList<>();
        RealtimeProperties props = new RealtimeProperties();

        // Node A
        RealtimeSessionManager smA = new RealtimeSessionManager(props);
        RealtimeGroupManager gmA = new RealtimeGroupManager();
        MockSharedBusBroadcaster bA = new MockSharedBusBroadcaster("node-A", bus);
        RealtimeTemplate templateA = new RealtimeTemplate(smA, gmA, props, bA);

        // Node B
        RealtimeSessionManager smB = new RealtimeSessionManager(props);
        RealtimeGroupManager gmB = new RealtimeGroupManager();
        MockSharedBusBroadcaster bB = new MockSharedBusBroadcaster("node-B", bus);
        RealtimeTemplate templateB = new RealtimeTemplate(smB, gmB, props, bB);

        // Node A 用户 1 加入 room-101
        List<RealtimeMessage<?>> nodeAUserMessages = new ArrayList<>();
        RealtimeSession user1 = createMockSession("user-1", "c1", 1L, nodeAUserMessages);
        smA.registerSession(user1);
        templateA.joinGroup("room-101", user1.getSessionKey());

        // Node B 用户 2 加入 room-101
        List<RealtimeMessage<?>> nodeBUserMessages = new ArrayList<>();
        RealtimeSession user2 = createMockSession("user-2", "c2", 1L, nodeBUserMessages);
        smB.registerSession(user2);
        templateB.joinGroup("room-101", user2.getSessionKey());

        // Node A 调用组播向 room-101 发消息
        RealtimeMessage<String> message = RealtimeMessage.of("chat", "welcome to room 101");
        int localCount = templateA.sendToGroup("room-101", message);

        // 验证：Node A 本地直发成功数 1，且用户 1 和用户 2 均只收到 1 条消息（无重复投递）
        assertThat(localCount).isEqualTo(1);
        assertThat(nodeAUserMessages).hasSize(1);
        assertThat(nodeAUserMessages.get(0).getData()).isEqualTo("welcome to room 101");

        assertThat(nodeBUserMessages).hasSize(1);
        assertThat(nodeBUserMessages.get(0).getData()).isEqualTo("welcome to room 101");
    }

    @Test
    @DisplayName("跨节点租户隔离广播：全集群匹配租户会话接收")
    void testCrossNodeTenantBroadcast() throws Exception {
        List<MockSharedBusBroadcaster> bus = new ArrayList<>();
        RealtimeProperties props = new RealtimeProperties();

        // Node A
        RealtimeSessionManager smA = new RealtimeSessionManager(props);
        RealtimeGroupManager gmA = new RealtimeGroupManager();
        MockSharedBusBroadcaster bA = new MockSharedBusBroadcaster("node-A", bus);
        RealtimeTemplate templateA = new RealtimeTemplate(smA, gmA, props, bA);

        // Node B
        RealtimeSessionManager smB = new RealtimeSessionManager(props);
        RealtimeGroupManager gmB = new RealtimeGroupManager();
        MockSharedBusBroadcaster bB = new MockSharedBusBroadcaster("node-B", bus);
        RealtimeTemplate templateB = new RealtimeTemplate(smB, gmB, props, bB);

        // Node A: 租户 100 用户
        List<RealtimeMessage<?>> nodeATenant100Msgs = new ArrayList<>();
        RealtimeSession userA100 = createMockSession("user-a1", "c1", 100L, nodeATenant100Msgs);
        smA.registerSession(userA100);

        // Node B: 租户 100 用户与租户 200 用户
        List<RealtimeMessage<?>> nodeBTenant100Msgs = new ArrayList<>();
        RealtimeSession userB100 = createMockSession("user-b1", "c2", 100L, nodeBTenant100Msgs);
        smB.registerSession(userB100);

        List<RealtimeMessage<?>> nodeBTenant200Msgs = new ArrayList<>();
        RealtimeSession userB200 = createMockSession("user-b2", "c3", 200L, nodeBTenant200Msgs);
        smB.registerSession(userB200);

        // Node A 向租户 100 广播
        RealtimeMessage<String> message = RealtimeMessage.of("tenant-notice", "tenant 100 announcement");
        int localCount = templateA.sendToTenant(100L, message);

        assertThat(localCount).isEqualTo(1);
        assertThat(nodeATenant100Msgs).hasSize(1);
        assertThat(nodeBTenant100Msgs).hasSize(1);
        assertThat(nodeBTenant200Msgs).isEmpty(); // 租户 200 绝不接收
    }

    @Test
    @DisplayName("跨节点踢人信令：NodeA 广播踢出，NodeB 上的目标用户被物理切断")
    void testCrossNodeKickout() throws Exception {
        List<MockSharedBusBroadcaster> bus = new ArrayList<>();
        RealtimeProperties props = new RealtimeProperties();

        // Node A
        RealtimeSessionManager smA = new RealtimeSessionManager(props);
        RealtimeGroupManager gmA = new RealtimeGroupManager();
        MockSharedBusBroadcaster bA = new MockSharedBusBroadcaster("node-A", bus);
        RealtimeTemplate templateA = new RealtimeTemplate(smA, gmA, props, bA);

        // Node B
        RealtimeSessionManager smB = new RealtimeSessionManager(props);
        RealtimeGroupManager gmB = new RealtimeGroupManager();
        MockSharedBusBroadcaster bB = new MockSharedBusBroadcaster("node-B", bus);
        RealtimeTemplate templateB = new RealtimeTemplate(smB, gmB, props, bB);

        // Node B 上存在用户 user-ban
        List<RealtimeMessage<?>> msgs = new ArrayList<>();
        RealtimeSession userBan = createMockSession("user-ban", "c1", 1L, msgs);
        smB.registerSession(userBan);
        assertThat(smB.containsSession(userBan.getSessionKey())).isTrue();

        // Node A 管理员执行踢人
        int localCount = templateA.kickoutUser("user-ban");
        assertThat(localCount).isEqualTo(0);

        // 验证 Node B 上该用户的长连接已被强制关闭并从注册表剔除
        assertThat(smB.containsSession(userBan.getSessionKey())).isFalse();
    }
}
