package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RFuture;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

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
}
