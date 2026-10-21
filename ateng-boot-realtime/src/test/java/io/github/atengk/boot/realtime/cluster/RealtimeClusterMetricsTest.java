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

import java.util.List;

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

        // 模拟 Redis Hash 中存在 3 个节点的上报数据 (NodeA=10, NodeB=25, NodeC=5)
        when(mockNodeMap.readAllValues()).thenReturn(List.of("10", "25", "5"));

        int totalCluster = reporter.getClusterOnlineCount();
        assertThat(totalCluster).isEqualTo(40);
        verify(redissonClient).getMap(eq("ateng:realtime:cluster:nodes"), eq(StringCodec.INSTANCE));
    }

    @Test
    @DisplayName("异常与空值保底: Redis 异常或无数据时优雅回退为当前节点本地计数")
    void testMetricsFallbackOnRedisError() {
        RedissonClusterMetricsReporter reporter = new RedissonClusterMetricsReporter(
                redissonClient, properties, sessionManager, clusterBroadcaster);

        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        sessionManager.registerSession(RealtimeSession.ofWebSocket(SessionKey.of("user-2", "c2"), mockWs));

        // 1. Redis 返回空集合 -> 保底返回本地连接数 1
        when(mockNodeMap.readAllValues()).thenReturn(List.of());
        assertThat(reporter.getClusterOnlineCount()).isEqualTo(1);

        // 2. Redis 抛出异常 -> 降级捕获并不崩溃，保底返回本地连接数 1
        when(mockNodeMap.readAllValues()).thenThrow(new RuntimeException("Redis connection timed out"));
        assertThat(reporter.getClusterOnlineCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("周期上报与注销清理: reportMetrics 写入本节点状态，cleanup 从 Hash 移除")
    void testReportAndCleanupLifecycle() {
        RedissonClusterMetricsReporter reporter = new RedissonClusterMetricsReporter(
                redissonClient, properties, sessionManager, clusterBroadcaster);

        WebSocketSession mockWs = mock(WebSocketSession.class);
        when(mockWs.isOpen()).thenReturn(true);
        sessionManager.registerSession(RealtimeSession.ofWebSocket(SessionKey.of("user-3", "c3"), mockWs));

        // 1. 单次上报写入本地在线数 1
        reporter.reportMetrics();
        verify(mockNodeMap).put(eq("node-test-1"), eq("1"));

        // 2. 清理注销
        reporter.cleanup();
        verify(mockNodeMap).remove(eq("node-test-1"));
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
