package io.github.atengk.boot.realtime.cluster;

import java.util.function.Consumer;

/**
 * 跨节点分布式集群广播门面接口
 * <p>
 * 提供面向分布式集群的消息发布抽象与生命周期管理，
 * 单机模式下提供保底降级实现，Redisson 就绪时自动升级为 Redis Pub/Sub 广播。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
public interface RealtimeClusterBroadcaster {

    /**
     * 向集群广播数据包
     *
     * @param packet 集群广播数据包
     */
    void broadcast(RealtimeClusterPacket packet);

    /**
     * 获取当前节点的唯一标识
     *
     * @return 节点唯一标识
     */
    String getNodeId();

    /**
     * 当前是否启用了集群广播能力
     *
     * @return true 为启用集群模式，false 为单机模式
     */
    boolean isClusterEnabled();

    /**
     * 注册远端广播报文接收消费者
     *
     * @param consumer 广播消费回调
     */
    void addConsumer(Consumer<RealtimeClusterPacket> consumer);
}
