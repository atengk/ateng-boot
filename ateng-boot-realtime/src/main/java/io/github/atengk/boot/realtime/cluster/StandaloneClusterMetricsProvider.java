package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.realtime.session.RealtimeSessionManager;

import java.util.Objects;

/**
 * 实时通信单机保底度量提供者实现
 * <p>
 * 当未配置集群或未引入分布式组件时激活，度量统计直接委托给本地会话中心。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
public class StandaloneClusterMetricsProvider implements RealtimeClusterMetricsProvider {

    private final RealtimeSessionManager sessionManager;

    public StandaloneClusterMetricsProvider(RealtimeSessionManager sessionManager) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
    }

    @Override
    public int getClusterOnlineCount() {
        return sessionManager.getOnlineCount();
    }
}
