package io.github.atengk.boot.realtime.cluster;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redisson 分布式 Hash 的集群节点在线度量上报与聚合统计器
 * <p>
 * 遵循高吞吐设计原则，各节点仅维护本地原子计数，由后台定时任务周期性（默认 5s）
 * 将本节点在线连接数以心跳方式写入 Redis Hash；查询全集群在线连接总数时从 Hash 聚合汇总，
 * 彻底消除高频连接上下线场景对 Redis 的原子写热点竞争。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RedissonClusterMetricsReporter implements RealtimeClusterMetricsProvider, SmartLifecycle {

    private final RedissonClient redissonClient;
    private final RealtimeProperties properties;
    private final RealtimeSessionManager sessionManager;
    private final RealtimeClusterBroadcaster clusterBroadcaster;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public RedissonClusterMetricsReporter(RedissonClient redissonClient,
                                          RealtimeProperties properties,
                                          RealtimeSessionManager sessionManager,
                                          RealtimeClusterBroadcaster clusterBroadcaster) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.clusterBroadcaster = Objects.requireNonNull(clusterBroadcaster, "clusterBroadcaster must not be null");
    }

    @Override
    public int getClusterOnlineCount() {
        if (!clusterBroadcaster.isClusterEnabled()) {
            return sessionManager.getOnlineCount();
        }
        try {
            String hashKey = getNodesHashKey();
            RMap<String, String> nodeMap = redissonClient.getMap(hashKey, StringCodec.INSTANCE);
            Collection<String> counts = nodeMap.readAllValues();
            if (counts == null || counts.isEmpty()) {
                return sessionManager.getOnlineCount();
            }
            int total = 0;
            for (String countStr : counts) {
                if (countStr != null && !countStr.isBlank()) {
                    try {
                        total += Integer.parseInt(countStr.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return Math.max(total, sessionManager.getOnlineCount());
        } catch (Throwable t) {
            log.warn("[Realtime-Cluster] 获取集群在线总数异常，回退本地统计: error={}", t.getMessage());
            return sessionManager.getOnlineCount();
        }
    }

    /**
     * 执行单次节点在线度量上报写入
     */
    public void reportMetrics() {
        if (!clusterBroadcaster.isClusterEnabled()) {
            return;
        }
        try {
            String hashKey = getNodesHashKey();
            String nodeId = clusterBroadcaster.getNodeId();
            int localCount = sessionManager.getOnlineCount();
            RMap<String, String> nodeMap = redissonClient.getMap(hashKey, StringCodec.INSTANCE);
            if (nodeMap != null) {
                nodeMap.put(nodeId, String.valueOf(localCount));
            }
            log.trace("[Realtime-Cluster] 节点度量上报成功: nodeId={}, localCount={}", nodeId, localCount);
        } catch (Throwable t) {
            log.warn("[Realtime-Cluster] 节点在线度量上报失败: nodeId={}, error={}",
                    clusterBroadcaster.getNodeId(), t.getMessage());
        }
    }

    /**
     * 节点下线或优雅停机时清理本节点在 Redis Hash 中的记录
     */
    public void cleanup() {
        stop();
        deregisterFromCluster();
    }

    /**
     * 从 Redis 集群度量 Hash 中注销本节点键值
     */
    private void deregisterFromCluster() {
        if (clusterBroadcaster.isClusterEnabled()) {
            try {
                String hashKey = getNodesHashKey();
                String nodeId = clusterBroadcaster.getNodeId();
                RMap<String, String> nodeMap = redissonClient.getMap(hashKey, StringCodec.INSTANCE);
                if (nodeMap != null) {
                    nodeMap.remove(nodeId);
                    log.info("[Realtime-Cluster] 节点已从集群在线度量 Hash 中注销: nodeId={}", nodeId);
                }
            } catch (Throwable t) {
                log.warn("[Realtime-Cluster] 清理集群在线节点度量失败: nodeId={}, error={}",
                        clusterBroadcaster.getNodeId(), t.getMessage());
            }
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            Duration interval = properties.getCluster().getMetricsInterval();
            long intervalMillis = (interval != null && !interval.isZero() && !interval.isNegative())
                    ? interval.toMillis()
                    : 5_000L;

            // 启动时立即上报一次初始状态
            reportMetrics();

            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                    1,
                    new NamedThreadFactory("realtime-metrics-reporter-", true)
            );
            executor.setRemoveOnCancelPolicy(true);
            this.scheduler = executor;
            this.scheduler.scheduleAtFixedRate(
                    this::reportMetrics,
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS
            );
            log.info("[Realtime-Cluster] 集群在线度量上报器已启动: interval={}ms, hashKey={}",
                    intervalMillis, getNodesHashKey());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            // 自动注销 Redis Hash 节点记录，防御孤立停机导致残留
            deregisterFromCluster();
            log.info("[Realtime-Cluster] 集群在线度量上报器已停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // 在优雅停机排空 (Phase = Integer.MAX_VALUE - 1000) 与死链心跳 (Phase = Integer.MAX_VALUE - 1100) 停止后再停止集群度量上报
        return Integer.MAX_VALUE - 1200;
    }

    @Override
    public boolean isAutoStartup() {
        return clusterBroadcaster.isClusterEnabled();
    }

    private String getNodesHashKey() {
        String key = properties.getCluster().getNodesHashKey();
        return (key != null && !key.isBlank()) ? key : "ateng:realtime:cluster:nodes";
    }
}
