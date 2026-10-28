package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 实时双向通信与流式会话健康探针
 * <p>
 * 探查 WebSocket 与 SSE 双通道活跃会话数、在线用户数及 Redis Pub/Sub 集群广播状态，
 * 继承 {@link AbstractSafeHealthIndicator} 实施异步超时切断与状态降级防卫。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class RealtimeHealthIndicator extends AbstractSafeHealthIndicator {

    private final ObjectProvider<RealtimeSessionManager> sessionManagerProvider;
    private final ObjectProvider<RealtimeClusterBroadcaster> broadcasterProvider;

    public RealtimeHealthIndicator(
            ObjectProvider<RealtimeSessionManager> sessionManagerProvider,
            ObjectProvider<RealtimeClusterBroadcaster> broadcasterProvider) {
        this(sessionManagerProvider, broadcasterProvider, (Duration) null, null);
    }

    public RealtimeHealthIndicator(
            ObjectProvider<RealtimeSessionManager> sessionManagerProvider,
            ObjectProvider<RealtimeClusterBroadcaster> broadcasterProvider,
            ObservabilityProperties properties) {
        this(sessionManagerProvider, broadcasterProvider, properties, null);
    }

    public RealtimeHealthIndicator(
            ObjectProvider<RealtimeSessionManager> sessionManagerProvider,
            ObjectProvider<RealtimeClusterBroadcaster> broadcasterProvider,
            ObservabilityProperties properties,
            Executor executor) {
        super(properties, executor);
        this.sessionManagerProvider = sessionManagerProvider;
        this.broadcasterProvider = broadcasterProvider;
    }

    public RealtimeHealthIndicator(
            ObjectProvider<RealtimeSessionManager> sessionManagerProvider,
            ObjectProvider<RealtimeClusterBroadcaster> broadcasterProvider,
            Duration timeout,
            Executor executor) {
        super(timeout, executor);
        this.sessionManagerProvider = sessionManagerProvider;
        this.broadcasterProvider = broadcasterProvider;
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        RealtimeSessionManager sessionManager = sessionManagerProvider != null ? sessionManagerProvider.getIfAvailable() : null;
        if (sessionManager == null) {
            builder.unknown().withDetail("message", "未检测到已装配的 RealtimeSessionManager 实例");
            return;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        int onlineCount = sessionManager.getOnlineCount();
        int onlineUserCount = sessionManager.getOnlineUserCount();
        int wsSessions = sessionManager.getSessionsByChannel(ChannelType.WEBSOCKET).size();
        int sseSessions = sessionManager.getSessionsByChannel(ChannelType.SSE).size();

        details.put("onlineCount", onlineCount);
        details.put("onlineUserCount", onlineUserCount);
        details.put("websocketSessions", wsSessions);
        details.put("sseSessions", sseSessions);

        RealtimeClusterBroadcaster broadcaster = broadcasterProvider != null ? broadcasterProvider.getIfAvailable() : null;
        if (broadcaster != null) {
            Map<String, Object> clusterDetails = new LinkedHashMap<>();
            clusterDetails.put("clusterEnabled", broadcaster.isClusterEnabled());
            clusterDetails.put("nodeId", broadcaster.getNodeId());
            details.put("cluster", clusterDetails);
        }

        builder.up().withDetails(details);
    }
}
