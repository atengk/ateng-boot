package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.cluster.LocalTcpClusterBridge;
import io.github.atengk.boot.tcp.cluster.RedisPubSubTcpClusterBridge;
import io.github.atengk.boot.tcp.cluster.TcpClusterBridge;
import io.github.atengk.boot.tcp.properties.AtengTcpProperties;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * TCP 集群跨节点推送桥接自动装配配置类
 * <p>
 * 支持根据配置与依赖自动决策启用 Redis 去中心化广播还是单机本地直发。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@AutoConfiguration(after = TcpServerAutoConfiguration.class)
@ConditionalOnClass(TcpServer.class)
public class TcpClusterAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RedisConnectionFactory.class, StringRedisTemplate.class})
    @ConditionalOnProperty(prefix = "ateng.boot.tcp.cluster.redis", name = "enabled", havingValue = "true")
    static class RedisClusterConfiguration {

        @Bean
        @ConditionalOnMissingBean(TcpClusterBridge.class)
        @ConditionalOnBean(StringRedisTemplate.class)
        public RedisPubSubTcpClusterBridge redisPubSubTcpClusterBridge(TcpSessionManager sessionManager,
                                                                       StringRedisTemplate redisTemplate,
                                                                       AtengTcpProperties properties) {
            return new RedisPubSubTcpClusterBridge(sessionManager, redisTemplate, properties.getCluster());
        }

        @Bean
        @ConditionalOnMissingBean(name = "tcpClusterRedisMessageListenerContainer")
        @ConditionalOnBean({RedisConnectionFactory.class, RedisPubSubTcpClusterBridge.class})
        public RedisMessageListenerContainer tcpClusterRedisMessageListenerContainer(
                RedisConnectionFactory connectionFactory,
                RedisPubSubTcpClusterBridge clusterBridge,
                AtengTcpProperties properties) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            String topic = properties.getCluster().getRedis().getTopic();
            container.addMessageListener(clusterBridge, new ChannelTopic(topic));
            return container;
        }
    }

    @Bean
    @ConditionalOnMissingBean(TcpClusterBridge.class)
    @ConditionalOnBean(TcpSessionManager.class)
    public LocalTcpClusterBridge localTcpClusterBridge(TcpSessionManager sessionManager) {
        return new LocalTcpClusterBridge(sessionManager);
    }
}
