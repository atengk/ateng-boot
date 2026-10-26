package io.github.atengk.boot.tcp.cluster;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClusterProperties;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * TCP 集群跨节点推送桥接两级自适应拓扑测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpClusterBridgeTest {

    @Test
    @DisplayName("LocalTcpClusterBridge 本地直发成功与未命中降级")
    void testLocalTcpClusterBridge() {
        TcpSessionManager sessionManager = new TcpSessionManager();
        LocalTcpClusterBridge bridge = new LocalTcpClusterBridge(sessionManager);

        EmbeddedChannel ch = new EmbeddedChannel();
        TcpSession session = new TcpSession("ses-1", ch);
        sessionManager.register(session);
        sessionManager.bind("ses-1", "DEV-001");

        // 本地存在该设备 -> 本地直发成功
        boolean sent = bridge.sendToCluster("DEV-001", TcpPayload.of("DIRECT_MSG"));
        assertThat(sent).isTrue();
        assertThat(ch.outboundMessages()).isNotEmpty();

        // 本地不存在该设备 -> 本地模式返回 false
        boolean notSent = bridge.sendToCluster("DEV-UNKNOWN", TcpPayload.of("UNKNOWN_MSG"));
        assertThat(notSent).isFalse();

        // 本地广播
        boolean broadcastSent = bridge.broadcastToCluster(TcpPayload.of("BROADCAST_MSG"));
        assertThat(broadcastSent).isTrue();
    }

    @Test
    @DisplayName("RedisPubSubTcpClusterBridge 两级自适应拓扑：本地命中优先直发，未命中广播至 Redis")
    void testRedisPubSubTwoTierTopology() {
        TcpSessionManager sessionManager = new TcpSessionManager();
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        TcpClusterProperties clusterProperties = new TcpClusterProperties();
        clusterProperties.getRedis().setEnabled(true);
        clusterProperties.getRedis().setTopic("test:topic");

        RedisPubSubTcpClusterBridge bridge = new RedisPubSubTcpClusterBridge(sessionManager, redisTemplate, clusterProperties);

        // 1. 本地连接设备 DEV-LOCAL
        EmbeddedChannel ch = new EmbeddedChannel();
        TcpSession localSession = new TcpSession("ses-local", ch);
        sessionManager.register(localSession);
        sessionManager.bind("ses-local", "DEV-LOCAL");

        // 2. 本地命中 -> 本地直发，不调用 Redis convertAndSend
        boolean localSent = bridge.sendToCluster("DEV-LOCAL", TcpPayload.of("HELLO_LOCAL"));
        assertThat(localSent).isTrue();
        assertThat(ch.outboundMessages()).isNotEmpty();
        Mockito.verifyNoInteractions(redisTemplate);

        // 3. 本地未命中 DEV-REMOTE -> 自动降级为 Redis Pub/Sub 跨节点广播
        boolean remoteSent = bridge.sendToCluster("DEV-REMOTE", "HELLO_REMOTE");
        assertThat(remoteSent).isTrue();
        verify(redisTemplate).convertAndSend(eq("test:topic"), anyString());
    }

    @Test
    @DisplayName("RedisPubSubTcpClusterBridge 接收跨节点广播与防回环守卫")
    void testRedisPubSubReceiveAndLoopGuard() {
        TcpSessionManager sessionManager = new TcpSessionManager();
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        TcpClusterProperties clusterProperties = new TcpClusterProperties();

        RedisPubSubTcpClusterBridge bridge = new RedisPubSubTcpClusterBridge(sessionManager, redisTemplate, clusterProperties);

        EmbeddedChannel ch = new EmbeddedChannel();
        TcpSession localSession = new TcpSession("ses-100", ch);
        sessionManager.register(localSession);
        sessionManager.bind("ses-100", "DEV-100");

        // 1. 模拟收到来自其它节点的广播（命中本机设备 DEV-100）
        TcpClusterBroadcastMessage foreignMsg = TcpClusterBroadcastMessage.of("DEV-100", "node-other", "MSG_FROM_NODE_B");
        String foreignJson = JsonUtil.toJsonString(foreignMsg);
        bridge.onMessage(new DefaultMessage("test:topic".getBytes(StandardCharsets.UTF_8), foreignJson.getBytes(StandardCharsets.UTF_8)), null);

        // 验证本机设备收到了消息
        assertThat(ch.outboundMessages()).isNotEmpty();

        ch.outboundMessages().clear();

        // 2. 模拟防回环守卫：收到自己节点发出的广播（sourceNodeId == bridge.nodeId）
        TcpClusterBroadcastMessage selfMsg = TcpClusterBroadcastMessage.of("DEV-100", bridge.getNodeId(), "SELF_MSG");
        String selfJson = JsonUtil.toJsonString(selfMsg);
        bridge.onMessage(new DefaultMessage("test:topic".getBytes(StandardCharsets.UTF_8), selfJson.getBytes(StandardCharsets.UTF_8)), null);

        // 验证被防回环守卫拦截，未重复向设备下发
        assertThat(ch.outboundMessages()).isEmpty();
    }
}
