package io.github.atengk.boot.realtime.cluster;

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
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 分布式集群在线度量统计与本地保底测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RealtimeClusterMetrics 集群在线度量与高吞吐统计测试")
class RealtimeClusterMetricsTest {

    private RealtimeProperties properties;
    private RealtimeSessionManager sessionManager;
    private RealtimeGroupManager groupManager;
    private RedissonClient redissonClient;
    private RealtimeClusterBroadcaster clusterBroadcaster;
    @SuppressWarnings("unchecked")
    private RMap<String, String> mockNodeMap = mock(RMap.class);

    @BeforeEach
    void setUp() {
        properties = new RealtimeProperties();
        sessionManager = new RealtimeSessionManager(properties);
        groupManager = new RealtimeGroupManager();

        redissonClient = mock(RedissonClient.class);
        when(redissonClient.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockNodeMap);

        clusterBroadcaster = mock(RealtimeClusterBroadcaster.class);
        when(clusterBroadcaster.isClusterEnabled()).thenReturn(true);
        when(clusterBroadcaster.getNodeId()).thenReturn("node-test-1");
    }

    @AfterEach
    void tearDown() {
        for (RealtimeSession s : sessionManager.getAllSessions()) {
            sessionManager.removeAndClose(s.getSessionKey(), CloseStatus.NORMAL);
        }
    }

    @Test
    @DisplayName("单机保底度量: StandaloneClusterMetricsProvider 直接返回当前节点在线连接数")
    void testStandaloneClusterMetricsProvider() {
        StandaloneClusterMetricsProvider provider = new StandaloneClusterMetricsProvider(sessionManager);

        assertThat(provider.getClusterOnlineCount()).isZero();

        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        sessionManager.registerSession(RealtimeSession.ofWebSocket(SessionKey.of("user-1", "c1"), mockWs));

        assertThat(provider.getClusterOnlineCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("集群聚合度量: RedissonClusterMetricsReporter 聚合全集群所有活跃节点在线数")
    void testRedissonClusterMetricsAggregation() {
        RedissonClusterMetricsReporter reporter = new RedissonClusterMetricsReporter(
                redissonClient, properties, sessionManager, clusterBroadcaster);
        assertThat(reporter.getPhase()).isEqualTo(Integer.MAX_VALUE - 1200);

        // 模拟 Redis Hash 中存在 3 个节点的上报数据 (NodeA=10, NodeB=25, NodeC=5)
        when(mockNodeMap.readAllMap()).thenReturn(Map.of("node-a", "10", "node-b", "25", "node-c", "5"));

        int totalCluster = reporter.getClusterOnlineCount();
        assertThat(totalCluster).isEqualTo(40);
        verify(redissonClient).getMap(eq("ateng:realtime:cluster:nodes"), eq(StringCodec.INSTANCE));
    }

    @Test
    @DisplayName("异常与空值保底: Redis 异常或无数据时优雅回退为当前节点本地计数")
    void testMetricsFallbackOnRedisError() {
        RedissonClusterMetricsReporter reporter = new RedissonClusterMetricsReporter(
                redissonClient, properties, sessionManager, clusterBroadcaster);
        assertThat(reporter.getPhase()).isEqualTo(Integer.MAX_VALUE - 1200);

        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        sessionManager.registerSession(RealtimeSession.ofWebSocket(SessionKey.of("user-2", "c2"), mockWs));

        // 1. Redis 返回空映射 -> 保底返回本地连接数 1
        when(mockNodeMap.readAllMap()).thenReturn(Map.of());
        assertThat(reporter.getClusterOnlineCount()).isEqualTo(1);

        // 2. Redis 抛出异常 -> 降级捕获并不崩溃，保底返回本地连接数 1
        when(mockNodeMap.readAllMap()).thenThrow(new RuntimeException("Redis connection timed out"));
        assertThat(reporter.getClusterOnlineCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("周期上报与注销清理: reportMetrics 写入本节点状态，cleanup 从 Hash 移除")
    void testReportAndCleanupLifecycle() {
        RedissonClusterMetricsReporter reporter = new RedissonClusterMetricsReporter(
                redissonClient, properties, sessionManager, clusterBroadcaster);
        assertThat(reporter.getPhase()).isEqualTo(Integer.MAX_VALUE - 1200);

        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        sessionManager.registerSession(RealtimeSession.ofWebSocket(SessionKey.of("user-3", "c3"), mockWs));

        // 1. 单次上报写入 localCount:timestamp 心跳格式，并清理过期死节点
        reporter.reportMetrics();
        verify(mockNodeMap).put(eq("node-test-1"), argThat(val -> val != null && val.startsWith("1:")));

        // 2. 清理注销
        reporter.cleanup();
        verify(mockNodeMap).remove(eq("node-test-1"));

        // 3. 验证独立 stop 也自动触发从 Hash 移除，防御孤立停机导致残留
        reporter.stop();
        verify(mockNodeMap, atLeastOnce()).remove(eq("node-test-1"));
    }

    @Test
    @DisplayName("死节点心跳惰性剔除: 忽略并清理超过 3 * metricsInterval（且不低于 15s）的失联节点，同时兼容旧版纯数字值")
    void testDeadNodeEvictionAndLegacyCompatibility() {
        properties.getCluster().setMetricsInterval(Duration.ofSeconds(5));
        RedissonClusterMetricsReporter reporter = new RedissonClusterMetricsReporter(
                redissonClient, properties, sessionManager, clusterBroadcaster);

        long now = System.currentTimeMillis();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("alive-node-a", "10:" + (now - 3_000L));
        entries.put("legacy-node-b", "20");
        entries.put("dead-node-crash", "99:" + (now - 20_000L));
        entries.put("invalid-node", "bad-data");
        when(mockNodeMap.readAllMap()).thenReturn(entries);

        // 查询全集群在线总数时忽略 dead-node-crash(99) 并将其从 Redis Hash 惰性剔除
        int clusterTotal = reporter.getClusterOnlineCount();
        assertThat(clusterTotal).isEqualTo(30);
        verify(mockNodeMap).remove("dead-node-crash");
        verify(mockNodeMap, never()).remove("alive-node-a");
        verify(mockNodeMap, never()).remove("legacy-node-b");

        // 定时上报 reportMetrics() 时同样顺带清理过期死节点
        reset(mockNodeMap);
        when(mockNodeMap.readAllMap()).thenReturn(entries);
        reporter.reportMetrics();
        verify(mockNodeMap).put(eq("node-test-1"), argThat(val -> val != null && val.startsWith("0:")));
        verify(mockNodeMap).remove("dead-node-crash");
    }

    @Test
    @DisplayName("RealtimeTemplate 门面正交 API: getClusterOnlineCount 正确代理与回退")
    void testTemplateClusterOnlineCountFacade() {
        RealtimeClusterMetricsProvider mockMetricsProvider = mock(RealtimeClusterMetricsProvider.class);
        when(mockMetricsProvider.getClusterOnlineCount()).thenReturn(88);

        RealtimeTemplate template = new RealtimeTemplate(
                sessionManager, groupManager, properties, clusterBroadcaster, null, mockMetricsProvider);

        // 1. 验证集群模式下调用 template.getClusterOnlineCount() 委托给 metricsProvider
        int clusterCount = template.getClusterOnlineCount();
        assertThat(clusterCount).isEqualTo(88);
        verify(mockMetricsProvider, times(1)).getClusterOnlineCount();

        // 2. 验证单机模式下平滑回退本地计数
        RealtimeTemplate standaloneTemplate = new RealtimeTemplate(sessionManager, groupManager, properties);
        assertThat(standaloneTemplate.getClusterOnlineCount()).isZero();
    }
}
