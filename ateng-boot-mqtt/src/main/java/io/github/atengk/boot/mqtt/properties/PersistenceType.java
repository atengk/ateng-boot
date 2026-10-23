package io.github.atengk.boot.mqtt.properties;

/**
 * MQTT 客户端离线报文与会话持久化存储类型
 *
 * @author Ateng
 * @since 2026-10-23
 */
public enum PersistenceType {

    /**
     * 纯内存持久化（默认推荐）：零磁盘 I/O 开销，适用于云原生容器无状态环境
     */
    MEMORY,

    /**
     * 本地文件持久化：持久化到指定本地磁盘目录，适用于高可靠边缘物理网关防断电离线追溯
     */
    FILE
}
