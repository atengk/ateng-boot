package io.github.atengk.boot.realtime.shutdown;

import io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.cluster.RedissonClusterMetricsReporter;
import io.github.atengk.boot.realtime.cluster.RedissonRealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时通信底座云原生优雅停机排空生命周期管理器
 * <p>
 * 接入 Spring SmartLifecycle 并在 Phase = Integer.MAX_VALUE - 1000 高优先级下执行，
 * 确保在底层 Servlet/Tomcat 物理网络层断开前完成：
 * <ul>
 *     <li>Phase 1: 握手熔断（拒绝新连接，返回 HTTP 503）；</li>
 *     <li>Phase 2: 状态码广播（向活跃会话广播 1012 SERVICE_RESTART 关闭帧，提示客户端随机抖动退避重连）；</li>
 *     <li>Phase 3: 缓冲排空等待（供并发安全装饰器冲刷在途数据帧）；</li>
 *     <li>Phase 4: 物理资源清理与集群度量/监听注销。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RealtimeGracefulShutdownHandler implements SmartLifecycle {

    private final RealtimeProperties properties;
    private final RealtimeSessionManager sessionManager;
    private final ObjectProvider<RealtimeClusterBroadcaster> clusterBroadcasterProvider;
    private final ObjectProvider<RedissonClusterMetricsReporter> metricsReporterProvider;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public RealtimeGracefulShutdownHandler(RealtimeProperties properties,
                                           RealtimeSessionManager sessionManager) {
        this(properties, sessionManager, null, null);
    }

    public RealtimeGracefulShutdownHandler(RealtimeProperties properties,
                                           RealtimeSessionManager sessionManager,
                                           ObjectProvider<RealtimeClusterBroadcaster> clusterBroadcasterProvider,
                                           ObjectProvider<RedissonClusterMetricsReporter> metricsReporterProvider) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.clusterBroadcasterProvider = clusterBroadcasterProvider;
        this.metricsReporterProvider = metricsReporterProvider;
    }

    /**
     * 当前是否正处于停机排空状态（用于握手入口 Fail-Fast 拦截）
     *
     * @return true 表示正在停机排空
     */
    public boolean isDraining() {
        return draining.get();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            draining.set(false);
            log.info("[Realtime] 优雅停机处理器已就绪 (SmartLifecycle Phase={})", getPhase());
        }
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        if (!running.compareAndSet(true, false)) {
            if (callback != null) {
                callback.run();
            }
            return;
        }

        log.info("[Realtime] 触发优雅停机流程，开始四阶渐进排空...");
        try {
            // 1. Phase 1: 握手熔断 (Handshake Ingress Cutoff)
            draining.set(true);
            Duration quietPeriod = properties.getShutdown().getQuietPeriod();
            if (quietPeriod != null && !quietPeriod.isZero() && !quietPeriod.isNegative()) {
                sleepQuietly(quietPeriod.toMillis());
            }

            // 2. Phase 2: 状态码广播 (Status Code Broadcast: 1012 SERVICE_RESTART)
            List<RealtimeSession> activeSessions = sessionManager.getAllSessions();
            log.info("[Realtime] 优雅停机 Phase 2: 向当前纳管的 [{}] 个活跃会话广播 1012 (SERVICE_RESTART) 状态码", activeSessions.size());
            for (RealtimeSession session : activeSessions) {
                try {
                    sessionManager.removeAndClose(session.getSessionKey(), RealtimeCloseStatus.SERVICE_RESTART);
                } catch (Throwable t) {
                    log.warn("[Realtime] 优雅停机关闭会话异常: sessionKey={}, error={}", session.getSessionKey(), t.getMessage());
                }
            }

            // 3. Phase 3: 缓冲排空等待 (Buffer Draining Wait)
            Duration drainTimeout = properties.getShutdown().getDrainTimeout();
            if (drainTimeout != null && !drainTimeout.isZero() && !drainTimeout.isNegative()) {
                log.info("[Realtime] 优雅停机 Phase 3: 等待在途缓冲区物理冲刷 (drainTimeout={}ms)...", drainTimeout.toMillis());
                sleepQuietly(drainTimeout.toMillis());
            }

            // 4. Phase 4: 物理注销与资源清理 (Physical Teardown & Unregistration)
            log.info("[Realtime] 优雅停机 Phase 4: 物理资源释放与集群注销...");
            if (metricsReporterProvider != null) {
                RedissonClusterMetricsReporter reporter = metricsReporterProvider.getIfAvailable();
                if (reporter != null) {
                    try {
                        reporter.cleanup();
                    } catch (Throwable t) {
                        log.warn("[Realtime] 清理集群在线度量异常: {}", t.getMessage());
                    }
                }
            }
            if (clusterBroadcasterProvider != null) {
                RealtimeClusterBroadcaster broadcaster = clusterBroadcasterProvider.getIfAvailable();
                if (broadcaster instanceof RedissonRealtimeClusterBroadcaster redissonBroadcaster) {
                    try {
                        redissonBroadcaster.destroy();
                    } catch (Throwable t) {
                        log.warn("[Realtime] 注销 Redisson 广播器监听异常: {}", t.getMessage());
                    }
                }
            }
        } finally {
            draining.set(false);
            log.info("[Realtime] 优雅停机排空完成");
            if (callback != null) {
                callback.run();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.getShutdown().isEnabled();
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
