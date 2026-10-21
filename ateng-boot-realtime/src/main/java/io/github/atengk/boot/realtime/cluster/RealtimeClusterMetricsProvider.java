package io.github.atengk.boot.realtime.cluster;

/**
 * 实时通信全集群在线度量提供者契约
 * <p>
 * 提供跨节点在线连接数聚合查询能力，当处于集群模式时从分布式协调中心（如 Redis）
 * 获取全网节点的连接总数；单机模式下平滑回退为当前节点统计。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
public interface RealtimeClusterMetricsProvider {

    /**
     * 获取全集群当前在线连接总数
     *
     * @return 全集群活跃连接总数
     */
    int getClusterOnlineCount();
}
