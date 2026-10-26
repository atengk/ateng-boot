package io.github.atengk.boot.tcp.cluster;

/**
 * TCP 微服务集群跨节点推送桥接 SPI 契约
 * <p>
 * 提供向分布式集群中跨节点连接的硬件设备下发报文的统一抽象，支持本地直发优先与去中心化广播两级拓扑。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public interface TcpClusterBridge {

    /**
     * 向集群中的指定设备下发数据报文
     *
     * @param deviceId 目标设备全局唯一标识
     * @param payload  下发报文载荷（支持 TcpPacket, TcpPayload, byte[], String 或 POJO）
     * @return true 表示成功触达本地设备或成功广播至分布式集群；false 表示目标不在线或投递失败
     */
    boolean sendToCluster(String deviceId, Object payload);

    /**
     * 向集群中的所有在线设备全量广播数据报文
     *
     * @param payload 广播报文载荷
     * @return true 表示广播触发成功
     */
    boolean broadcastToCluster(Object payload);
}
