package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * sourceNodeId 防反射机制与自发自收过滤测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("集群广播防反射过滤测试")
class RealtimeClusterAntiReflectionTest {

    @Test
    @DisplayName("自发自收广播报文必须被静默丢弃，远端报文正常分发")
    void testAntiReflectionFiltering() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(redissonClient.getTopic(anyString())).thenReturn(topic);

        AtomicReference<MessageListener<String>> listenerHolder = new AtomicReference<>();
        when(topic.addListener(eq(String.class), any())).thenAnswer(invocation -> {
            MessageListener<String> listener = invocation.getArgument(1);
            listenerHolder.set(listener);
            return 1001;
        });

        RealtimeProperties properties = new RealtimeProperties();
        RedissonRealtimeClusterBroadcaster broadcaster = new RedissonRealtimeClusterBroadcaster(redissonClient, properties);
        broadcaster.afterPropertiesSet();

        String selfNodeId = broadcaster.getNodeId();
        AtomicInteger consumedCount = new AtomicInteger(0);
        broadcaster.addConsumer(packet -> consumedCount.incrementAndGet());

        // 1. 模拟收到自身发送的消息包（sourceNodeId == selfNodeId）
        RealtimeClusterPacket selfPacket = RealtimeClusterPacket.ofUser(
                selfNodeId, "user-001", RealtimeMessage.of("test", "hello from self"));
        listenerHolder.get().onMessage(null, JsonUtil.toJsonString(selfPacket));

        // 验证防反射：被丢弃，消费者计数为 0
        assertThat(consumedCount.get()).isEqualTo(0);

        // 2. 模拟收到远端节点发送的消息包（sourceNodeId != selfNodeId）
        RealtimeClusterPacket remotePacket = RealtimeClusterPacket.ofUser(
                "remote-node-99", "user-001", RealtimeMessage.of("test", "hello from remote"));
        listenerHolder.get().onMessage(null, JsonUtil.toJsonString(remotePacket));

        // 验证远端消费：消费者计数递增为 1
        assertThat(consumedCount.get()).isEqualTo(1);
    }
}
