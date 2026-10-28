package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RealtimeHealthIndicator 实时通信探针单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("RealtimeHealthIndicator 实时通信健康探针单元测试")
class RealtimeHealthIndicatorTest {

    @Test
    @DisplayName("未装配 RealtimeSessionManager 时，返回 UNKNOWN 状态")
    void testNoSessionManagerConfigured() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        RealtimeHealthIndicator indicator = new RealtimeHealthIndicator(
                beanFactory.getBeanProvider(RealtimeSessionManager.class),
                beanFactory.getBeanProvider(RealtimeClusterBroadcaster.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("message", "未检测到已装配的 RealtimeSessionManager 实例");
    }

    @Test
    @DisplayName("装配会话管理器与集群广播器，正常返回 UP 状态与在线指标")
    void testSessionManagerWithClusterBroadcasterUp() {
        RealtimeSessionManager mockManager = mock(RealtimeSessionManager.class);
        when(mockManager.getOnlineCount()).thenReturn(5);
        when(mockManager.getOnlineUserCount()).thenReturn(3);
        when(mockManager.getSessionsByChannel(ChannelType.WEBSOCKET)).thenReturn(List.of(mock(RealtimeSession.class), mock(RealtimeSession.class)));
        when(mockManager.getSessionsByChannel(ChannelType.SSE)).thenReturn(List.of(mock(RealtimeSession.class)));

        RealtimeClusterBroadcaster mockBroadcaster = mock(RealtimeClusterBroadcaster.class);
        when(mockBroadcaster.isClusterEnabled()).thenReturn(true);
        when(mockBroadcaster.getNodeId()).thenReturn("node-shanghai-1");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("realtimeSessionManager", mockManager);
        beanFactory.addBean("realtimeClusterBroadcaster", mockBroadcaster);

        RealtimeHealthIndicator indicator = new RealtimeHealthIndicator(
                beanFactory.getBeanProvider(RealtimeSessionManager.class),
                beanFactory.getBeanProvider(RealtimeClusterBroadcaster.class),
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("onlineCount", 5);
        assertThat(health.getDetails()).containsEntry("onlineUserCount", 3);
        assertThat(health.getDetails()).containsEntry("websocketSessions", 2);
        assertThat(health.getDetails()).containsEntry("sseSessions", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> clusterDetails = (Map<String, Object>) health.getDetails().get("cluster");
        assertThat(clusterDetails).containsEntry("clusterEnabled", true);
        assertThat(clusterDetails).containsEntry("nodeId", "node-shanghai-1");
    }

    @Test
    @DisplayName("单机模式下无集群广播器，仍正常返回 UP 状态与会话数")
    void testStandaloneModeUp() {
        RealtimeSessionManager mockManager = mock(RealtimeSessionManager.class);
        when(mockManager.getOnlineCount()).thenReturn(0);
        when(mockManager.getOnlineUserCount()).thenReturn(0);
        when(mockManager.getSessionsByChannel(ChannelType.WEBSOCKET)).thenReturn(List.of());
        when(mockManager.getSessionsByChannel(ChannelType.SSE)).thenReturn(List.of());

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("realtimeSessionManager", mockManager);

        RealtimeHealthIndicator indicator = new RealtimeHealthIndicator(
                beanFactory.getBeanProvider(RealtimeSessionManager.class),
                beanFactory.getBeanProvider(RealtimeClusterBroadcaster.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("onlineCount", 0);
        assertThat(health.getDetails()).doesNotContainKey("cluster");
    }
}
