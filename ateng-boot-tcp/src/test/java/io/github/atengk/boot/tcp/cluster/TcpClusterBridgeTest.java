package io.github.atengk.boot.tcp.cluster;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClusterProperties;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * TCP 集群跨节点推送桥接两级自适应拓扑测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
class TcpClusterBridgeTest {

    @AfterEach
    void cleanupContext() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

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

    @Test
    @DisplayName("验证 TcpPacket 跨节点广播序列化与反序列化全量保留 Command、RequestId 与 Headers 元数据")
    void testBroadcastMessagePacketPreservation() {
        TcpPacket originalPacket = TcpPacket.builder()
                .command("CONFIG_SYNC")
                .requestId("REQ-SYNC-888")
                .deviceId("DEV-DEVICE-999")
                .payload(TcpPayload.of("{\"sampleKey\":\"sampleVal\"}"))
                .build();
        originalPacket.getHeaders().put("traceId", "T-123456");
        originalPacket.getHeaders().put("version", 2);

        TcpClusterBroadcastMessage message = TcpClusterBroadcastMessage.of("DEV-DEVICE-999", "node-master", originalPacket);
        assertThat(message.getPayloadType()).isEqualTo("PACKET");
        assertThat(message.getPacketCommand()).isEqualTo("CONFIG_SYNC");
        assertThat(message.getPacketRequestId()).isEqualTo("REQ-SYNC-888");
        assertThat(message.getPacketDeviceId()).isEqualTo("DEV-DEVICE-999");
        assertThat(message.getPacketHeaders()).containsEntry("traceId", "T-123456");

        // 模拟 Redis JSON 序列化与跨节点反序列化
        String json = JsonUtil.toJsonString(message);
        TcpClusterBroadcastMessage deserialized = JsonUtil.parseObject(json, TcpClusterBroadcastMessage.class);
        assertThat(deserialized).isNotNull();

        Object resolved = deserialized.resolvePayload();
        assertThat(resolved).isInstanceOf(TcpPacket.class);
        TcpPacket packet = (TcpPacket) resolved;

        assertThat(packet.getCommand()).isEqualTo("CONFIG_SYNC");
        assertThat(packet.getRequestId()).isEqualTo("REQ-SYNC-888");
        assertThat(packet.getDeviceId()).isEqualTo("DEV-DEVICE-999");
        assertThat(packet.getPayload().asString()).isEqualTo("{\"sampleKey\":\"sampleVal\"}");
        assertThat(packet.getHeaders()).containsEntry("traceId", "T-123456");
    }

    @Test
    @DisplayName("验证 LocalTcpClusterBridge 单机分组组播逻辑")
    void testLocalTcpClusterBridgeGroupMulticast() {
        TcpSessionManager sessionManager = new TcpSessionManager();
        LocalTcpClusterBridge bridge = new LocalTcpClusterBridge(sessionManager);

        EmbeddedChannel ch1 = new EmbeddedChannel();
        TcpSession session1 = new TcpSession("ses-1", ch1);
        sessionManager.register(session1);
        sessionManager.addToGroup("FLEET_A", "ses-1");

        EmbeddedChannel ch2 = new EmbeddedChannel();
        TcpSession session2 = new TcpSession("ses-2", ch2);
        sessionManager.register(session2);
        sessionManager.addToGroup("FLEET_B", "ses-2");

        // 1. 向 FLEET_A 组播 -> 仅 session1 收到
        boolean sent = bridge.sendGroupToCluster("FLEET_A", "FLEET_A_MSG");
        assertThat(sent).isTrue();
        assertThat(ch1.outboundMessages()).isNotEmpty();
        assertThat(ch2.outboundMessages()).isEmpty();

        // 2. 向不存在的分组组播 -> 返回 false
        boolean notSent = bridge.sendGroupToCluster("FLEET_UNKNOWN", "EMPTY_MSG");
        assertThat(notSent).isFalse();
    }

    @Test
    @DisplayName("验证 RedisPubSubTcpClusterBridge 跨节点分组组播发信、对端接收路由与防回环守卫")
    void testRedisPubSubGroupMulticastSendAndReceive() {
        TcpSessionManager sessionManager = new TcpSessionManager();
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        TcpClusterProperties clusterProperties = new TcpClusterProperties();
        clusterProperties.getRedis().setEnabled(true);
        clusterProperties.getRedis().setTopic("test:cluster:topic");

        RedisPubSubTcpClusterBridge bridge = new RedisPubSubTcpClusterBridge(sessionManager, redisTemplate, clusterProperties);

        // 注册两个会话，分别加入 FLEET_ALPHA 与 FLEET_BETA
        EmbeddedChannel chAlpha = new EmbeddedChannel();
        TcpSession sessionAlpha = new TcpSession("ses-alpha", chAlpha);
        sessionManager.register(sessionAlpha);
        sessionManager.addToGroup("FLEET_ALPHA", "ses-alpha");

        EmbeddedChannel chBeta = new EmbeddedChannel();
        TcpSession sessionBeta = new TcpSession("ses-beta", chBeta);
        sessionManager.register(sessionBeta);
        sessionManager.addToGroup("FLEET_BETA", "ses-beta");

        // 1. 发起 FLEET_ALPHA 组播 -> 本地直发 + Redis 跨节点广播
        boolean sent = bridge.sendGroupToCluster("FLEET_ALPHA", "DISPATCH_ALPHA");
        assertThat(sent).isTrue();
        assertThat(chAlpha.outboundMessages()).isNotEmpty();
        assertThat(chBeta.outboundMessages()).isEmpty();
        verify(redisTemplate).convertAndSend(eq("test:cluster:topic"), anyString());

        chAlpha.outboundMessages().clear();

        // 2. 模拟从 Redis 收到其它节点广播的分组组播 (group = FLEET_BETA)
        TcpClusterBroadcastMessage remoteGroupMsg = TcpClusterBroadcastMessage.ofGroup("FLEET_BETA", "node-remote-99", "BROADCAST_TO_BETA");
        String json = JsonUtil.toJsonString(remoteGroupMsg);
        bridge.onMessage(new DefaultMessage("test:cluster:topic".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8)), null);

        // 验证 FLEET_BETA 收到，FLEET_ALPHA 未收到
        assertThat(chBeta.outboundMessages()).isNotEmpty();
        assertThat(chAlpha.outboundMessages()).isEmpty();

        chBeta.outboundMessages().clear();

        // 3. 模拟自身节点的防回环守卫
        TcpClusterBroadcastMessage selfLoopMsg = TcpClusterBroadcastMessage.ofGroup("FLEET_BETA", bridge.getNodeId(), "LOOP_MSG");
        String loopJson = JsonUtil.toJsonString(selfLoopMsg);
        bridge.onMessage(new DefaultMessage("test:cluster:topic".getBytes(StandardCharsets.UTF_8), loopJson.getBytes(StandardCharsets.UTF_8)), null);

        assertThat(chBeta.outboundMessages()).isEmpty();
    }

    @Test
    @DisplayName("验证 TcpServerTemplate 门面 sendGroupToCluster 方法")
    void testTcpServerTemplateSendGroupToCluster() {
        TcpSessionManager sessionManager = new TcpSessionManager();
        TcpServerTemplate template = new TcpServerTemplate(sessionManager);

        EmbeddedChannel ch = new EmbeddedChannel();
        TcpSession session = new TcpSession("ses-group-1", ch);
        sessionManager.register(session);
        sessionManager.addToGroup("ZONE_1", "ses-group-1");

        // 无 clusterBridge 时退化为本地组播
        boolean sent = template.sendGroupToCluster("ZONE_1", "ZONE_PAYLOAD");
        assertThat(sent).isTrue();
        assertThat(ch.outboundMessages()).isNotEmpty();

        // group 为 null 时防御
        boolean nullGroupSent = template.sendGroupToCluster(null, "FAIL_MSG");
        assertThat(nullGroupSent).isFalse();
    }

    @Test
    @DisplayName("验证跨节点集群广播透传四维上下文并在接收处理后无条件清理恢复现场")
    void testRedisPubSubPropagatesAndCleansUpFourDimensionalContext() {
        TcpSessionManager sessionManager = new TcpSessionManager();
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        TcpClusterProperties clusterProperties = new TcpClusterProperties();
        clusterProperties.getRedis().setEnabled(true);
        clusterProperties.getRedis().setTopic("test:ctx:topic");

        RedisPubSubTcpClusterBridge bridge = new RedisPubSubTcpClusterBridge(sessionManager, redisTemplate, clusterProperties);

        // 1. 在节点 A 线程设置四维上下文，构造跨节点广播消息并序列化
        MDC.put("traceId", "trace-cluster-999");
        TenantContextHolder.setTenantId("tenant-alpha");
        TenantContextHolder.setIgnoreTenant(true);
        UserContextHolder.set("user-admin-01");

        TcpClusterBroadcastMessage outboundMsg = TcpClusterBroadcastMessage.ofGroup("GRP-CTX", "node-source-A", "CTX_PAYLOAD");
        assertThat(outboundMsg.getContextHeaders())
                .containsEntry(MessageHeaderConstants.TRACE_ID, "trace-cluster-999")
                .containsEntry(MessageHeaderConstants.TENANT_ID, "tenant-alpha")
                .containsEntry(MessageHeaderConstants.USER_ID, "user-admin-01")
                .containsEntry(MessageHeaderConstants.IGNORE_TENANT, "true");

        String wireJson = JsonUtil.toJsonString(outboundMsg);

        // 2. 模拟节点 B（先预设完全不同的工作线程原始现场，再验证执行中激活、执行后恢复且另一场景零残留）
        EmbeddedChannel ch = new EmbeddedChannel();
        TcpSession spySession = Mockito.spy(new TcpSession("ses-ctx-1", ch));
        AtomicReference<String> observedTrace = new AtomicReference<>();
        AtomicReference<String> observedTenant = new AtomicReference<>();
        AtomicReference<String> observedUser = new AtomicReference<>();
        AtomicBoolean observedIgnore = new AtomicBoolean(false);

        doAnswer(invocation -> {
            observedTrace.set(MDC.get("traceId"));
            observedTenant.set(TenantContextHolder.getTenantId());
            observedUser.set(UserContextHolder.get());
            observedIgnore.set(TenantContextHolder.isIgnoreTenant());
            return invocation.callRealMethod();
        }).when(spySession).send(any());

        sessionManager.register(spySession);
        sessionManager.addToGroup("GRP-CTX", "ses-ctx-1");

        // 场景 A：空现场接收线程，执行中激活远端四维上下文，执行后零残留
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();

        bridge.onMessage(new DefaultMessage("test:ctx:topic".getBytes(StandardCharsets.UTF_8), wireJson.getBytes(StandardCharsets.UTF_8)), null);

        assertThat(observedTrace.get()).isEqualTo("trace-cluster-999");
        assertThat(observedTenant.get()).isEqualTo("tenant-alpha");
        assertThat(observedUser.get()).isEqualTo("user-admin-01");
        assertThat(observedIgnore.get()).isTrue();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        assertThat(UserContextHolder.get()).isNull();

        // 场景 B：有预设原始现场的工作线程，执行后精确恢复原始现场
        MDC.put("traceId", "prev-trace");
        TenantContextHolder.setTenantId("prev-tenant");
        UserContextHolder.set("prev-user");
        TenantContextHolder.setIgnoreTenant(false);

        bridge.onMessage(new DefaultMessage("test:ctx:topic".getBytes(StandardCharsets.UTF_8), wireJson.getBytes(StandardCharsets.UTF_8)), null);

        assertThat(MDC.get("traceId")).isEqualTo("prev-trace");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("prev-tenant");
        assertThat(UserContextHolder.get()).isEqualTo("prev-user");
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
    }
}
