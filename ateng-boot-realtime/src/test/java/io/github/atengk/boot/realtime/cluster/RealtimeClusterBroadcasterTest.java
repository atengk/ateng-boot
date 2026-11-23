package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.group.RealtimeGroupManager;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.spi.RealtimeOfflineMessageProvider;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RFuture;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 集群广播门面及实现单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("集群广播门面及实现测试")
class RealtimeClusterBroadcasterTest {

    @Test
    @DisplayName("单机保底广播器行为验证")
    void testStandaloneBroadcaster() {
        StandaloneRealtimeClusterBroadcaster broadcaster = new StandaloneRealtimeClusterBroadcaster();
        assertThat(broadcaster.isClusterEnabled()).isFalse();
        assertThat(broadcaster.getNodeId()).isNotBlank();

        // 广播不报错（No-Op）
        RealtimeClusterPacket packet = RealtimeClusterPacket.ofAll(broadcaster.getNodeId(), RealtimeMessage.of("ping", "pong"));
        broadcaster.broadcast(packet);

        Consumer<RealtimeClusterPacket> noopConsumer = p -> {};
        broadcaster.addConsumer(noopConsumer);
        broadcaster.removeConsumer(noopConsumer);

        assertThatThrownBy(() -> broadcaster.addConsumer(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> broadcaster.removeConsumer(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Redisson 广播发布与防反射自发自收过滤验证")
    void testRedissonBroadcaster() throws Exception {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(redissonClient.getTopic(anyString())).thenReturn(topic);

        @SuppressWarnings("unchecked")
        RFuture<Long> future = mock(RFuture.class);
        when(topic.publishAsync(anyString())).thenReturn(future);

        AtomicReference<MessageListener<String>> listenerRef = new AtomicReference<>();
        when(topic.addListener(eq(String.class), any())).thenAnswer(invocation -> {
            MessageListener<String> listener = invocation.getArgument(1);
            listenerRef.set(listener);
            return 101;
        });

        RealtimeProperties properties = new RealtimeProperties();
        properties.getCluster().setTopic("test:realtime:broadcast");

        RedissonRealtimeClusterBroadcaster broadcaster = new RedissonRealtimeClusterBroadcaster(redissonClient, properties);
        broadcaster.afterPropertiesSet();

        assertThat(broadcaster.isClusterEnabled()).isTrue();
        String selfNodeId = broadcaster.getNodeId();
        assertThat(selfNodeId).isNotBlank();

        // 1. 验证主动广播向 Topic 异步发布 JSON 文本
        RealtimeClusterPacket outgoing = RealtimeClusterPacket.ofUser(selfNodeId, "user-1", RealtimeMessage.of("msg", "hello"));
        broadcaster.broadcast(outgoing);
        verify(topic, times(1)).publishAsync(anyString());

        // 2. 挂载消息接收消费回调
        AtomicReference<RealtimeClusterPacket> receivedPacket = new AtomicReference<>();
        Consumer<RealtimeClusterPacket> consumer = receivedPacket::set;
        broadcaster.addConsumer(consumer);

        assertThatThrownBy(() -> broadcaster.addConsumer(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> broadcaster.removeConsumer(null))
                .isInstanceOf(NullPointerException.class);

        // 3. 模拟收到自身发送的消息 -> 触发防反射过滤（必须丢弃，receivedPacket 为空）
        String selfJson = JsonUtil.toJsonString(outgoing);
        listenerRef.get().onMessage(null, selfJson);
        assertThat(receivedPacket.get()).isNull();

        // 4. 模拟收到远端节点发送的消息 -> 正常接收并触发回调
        RealtimeClusterPacket remotePacket = RealtimeClusterPacket.ofUser("remote-node-2", "user-1", RealtimeMessage.of("msg", "from remote"));
        String remoteJson = JsonUtil.toJsonString(remotePacket);
        listenerRef.get().onMessage(null, remoteJson);
        assertThat(receivedPacket.get()).isNotNull();
        assertThat(receivedPacket.get().getSourceNodeId()).isEqualTo("remote-node-2");
        assertThat(receivedPacket.get().getTargetKey()).isEqualTo("user-1");

        // 5. 验证 removeConsumer 注销后不再接收消息
        receivedPacket.set(null);
        broadcaster.removeConsumer(consumer);
        listenerRef.get().onMessage(null, remoteJson);
        assertThat(receivedPacket.get()).isNull();

        // 6. 验证优雅销毁注销 Topic 监听
        broadcaster.destroy();
        verify(topic, times(1)).removeListener(101);
    }

    @Test
    @DisplayName("防崩溃幽灵在线: 节点级幂等同步、isUserOnline 过滤死节点并触发离线消息暂存")
    void testNodeLevelPresenceAndDeadNodeEviction() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RealtimeProperties properties = new RealtimeProperties();
        properties.getCluster().setMetricsInterval(Duration.ofSeconds(5));

        Map<String, String> nodesHeartbeatBacking = new ConcurrentHashMap<>();
        Map<String, Map<String, String>> userPresenceBacking = new ConcurrentHashMap<>();
        Map<String, Set<String>> nodeUsersBacking = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        RMap<String, String> mockNodesMap = mockMap(nodesHeartbeatBacking);
        when(redissonClient.<String, String>getMap(eq("ateng:realtime:cluster:nodes"), eq(StringCodec.INSTANCE)))
                .thenReturn(mockNodesMap);
        when(redissonClient.<String, String>getMap(startsWith("ateng:realtime:cluster:nodes:users:"), eq(StringCodec.INSTANCE)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    return mockMap(userPresenceBacking.computeIfAbsent(key, k -> new ConcurrentHashMap<>()));
                });
        when(redissonClient.<String>getSet(startsWith("ateng:realtime:cluster:nodes:node-users:"), eq(StringCodec.INSTANCE)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    return mockSet(nodeUsersBacking.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()));
                });

        RedissonRealtimeClusterBroadcaster broadcaster = new RedissonRealtimeClusterBroadcaster(redissonClient, properties);
        String selfNodeId = broadcaster.getNodeId();
        String selfNodeUsersKey = "ateng:realtime:cluster:nodes:node-users:" + selfNodeId;

        // 1. 本节点同一用户多会话幂等上下线同步（重复注册同 SessionKey 不会虚增，全部关闭后移除节点条目）
        WebSocketSession ws1 = mock(WebSocketSession.class);
        WebSocketSession ws2 = mock(WebSocketSession.class);
        RealtimeSession s1 = RealtimeSession.ofWebSocket(SessionKey.of("u-sync", "web", "c1"), ws1);
        RealtimeSession s2 = RealtimeSession.ofWebSocket(SessionKey.of("u-sync", "app", "c2"), ws2);

        broadcaster.onSessionRegistered(s1);
        broadcaster.onSessionRegistered(s1);
        broadcaster.onSessionRegistered(s2);
        Map<String, String> syncMap = userPresenceBacking.get("ateng:realtime:cluster:nodes:users:u-sync");
        assertThat(syncMap).containsEntry(selfNodeId, "2");
        assertThat(nodeUsersBacking.get(selfNodeUsersKey)).contains("u-sync");

        broadcaster.onSessionClosed(s1, CloseStatus.NORMAL);
        assertThat(syncMap).containsEntry(selfNodeId, "1");
        broadcaster.onSessionClosed(s2, CloseStatus.NORMAL);
        assertThat(syncMap).doesNotContainKey(selfNodeId);
        assertThat(nodeUsersBacking.get(selfNodeUsersKey)).doesNotContain("u-sync");

        // 2. 模拟远端节点 dead-node 崩溃：心跳已超时（> 3 * metricsInterval = 15s）且未触发 onSessionClosed
        long now = System.currentTimeMillis();
        nodesHeartbeatBacking.put("dead-node", "1:" + (now - 20_000L));
        nodesHeartbeatBacking.put("alive-node", "3:" + (now - 2_000L));

        userPresenceBacking.computeIfAbsent("ateng:realtime:cluster:nodes:users:user-ghost", k -> new ConcurrentHashMap<>())
                .put("dead-node", "1");
        Map<String, String> multiNodeMap = userPresenceBacking.computeIfAbsent(
                "ateng:realtime:cluster:nodes:users:user-multi", k -> new ConcurrentHashMap<>());
        multiNodeMap.put("dead-node", "2");
        multiNodeMap.put("alive-node", "1");

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeOfflineMessageProvider offlineProvider = mock(RealtimeOfflineMessageProvider.class);
        RealtimeTemplate template = new RealtimeTemplate(
                sessionManager, new RealtimeGroupManager(), properties, broadcaster, offlineProvider);

        // user-ghost 仅在失联死节点残留记录 -> isUserOnline 为 false，清理死节点记录，且 sendToUser 触发离线暂存
        assertThat(template.isUserOnline("user-ghost")).isFalse();
        assertThat(userPresenceBacking.get("ateng:realtime:cluster:nodes:users:user-ghost")).doesNotContainKey("dead-node");

        RealtimeMessage<String> persistentMsg = RealtimeMessage.<String>builder()
                .id("offline-972-1")
                .event("chat:msg")
                .data("hello offline")
                .persistent(true)
                .build();
        template.sendToUser("user-ghost", persistentMsg);
        verify(offlineProvider, times(1)).saveOfflineMessage("user-ghost", persistentMsg);

        // user-multi 在死节点有残留，但在存活节点 alive-node 仍在线 -> 返回 true 并同时清理死节点残留
        assertThat(template.isUserOnline("user-multi")).isTrue();
        assertThat(multiNodeMap).doesNotContainKey("dead-node");
        assertThat(multiNodeMap).containsEntry("alive-node", "1");

        // 3. 验证 destroy() 停机注销会清空本节点尚未关闭的在线用户残留与节点反向索引
        RealtimeSession sDestroy = RealtimeSession.ofWebSocket(SessionKey.of("u-destroy", "web", "d1"), ws1);
        broadcaster.onSessionRegistered(sDestroy);
        assertThat(userPresenceBacking.get("ateng:realtime:cluster:nodes:users:u-destroy")).containsEntry(selfNodeId, "1");
        assertThat(nodeUsersBacking.get(selfNodeUsersKey)).contains("u-destroy");
        broadcaster.destroy();
        assertThat(userPresenceBacking.get("ateng:realtime:cluster:nodes:users:u-destroy")).doesNotContainKey(selfNodeId);
        assertThat(nodeUsersBacking.get(selfNodeUsersKey)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private RSet<String> mockSet(Set<String> backing) {
        RSet<String> set = mock(RSet.class);
        when(set.add(anyString())).thenAnswer(inv -> backing.add(inv.getArgument(0)));
        when(set.remove(any())).thenAnswer(inv -> backing.remove(inv.getArgument(0)));
        when(set.readAll()).thenAnswer(inv -> Set.copyOf(backing));
        when(set.delete()).thenAnswer(inv -> {
            boolean existed = !backing.isEmpty();
            backing.clear();
            return existed;
        });
        return set;
    }

    @SuppressWarnings("unchecked")
    private RMap<String, String> mockMap(Map<String, String> backing) {
        RMap<String, String> map = mock(RMap.class);
        when(map.put(anyString(), anyString())).thenAnswer(inv -> backing.put(inv.getArgument(0), inv.getArgument(1)));
        when(map.get(any())).thenAnswer(inv -> backing.get(inv.getArgument(0)));
        when(map.remove(any())).thenAnswer(inv -> backing.remove(inv.getArgument(0)));
        when(map.readAllMap()).thenAnswer(inv -> Map.copyOf(backing));
        when(map.delete()).thenAnswer(inv -> {
            boolean existed = !backing.isEmpty();
            backing.clear();
            return existed;
        });
        return map;
    }
}
