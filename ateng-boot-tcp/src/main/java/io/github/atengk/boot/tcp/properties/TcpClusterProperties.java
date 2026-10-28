package io.github.atengk.boot.tcp.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * TCP 微服务集群跨节点协同配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
public class TcpClusterProperties {

    /**
     * 基于 Redis Pub/Sub 的集群跨节点广播配置
     */
    @NestedConfigurationProperty
    private TcpRedisClusterProperties redis = new TcpRedisClusterProperties();

    /**
     * Redis 集群广播配置子类
     */
    @Getter
    @Setter
    public static class TcpRedisClusterProperties {

        /**
         * 是否启用基于 Redis Pub/Sub 的跨节点指令广播（默认 false）
         */
        private boolean enabled = false;

        /**
         * 跨节点广播订阅的主题通道（默认 ateng:tcp:cluster:broadcast）
         */
        private String topic = "ateng:tcp:cluster:broadcast";
    }
}
