package io.github.atengk.boot.redis.autoconfigure;

import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 增强基础设施与分布式协同自动装配类
 * 实现 Spring Data Redis (Lettuce) 与 Redisson 双核客户端融合装配
 *
 * @author Ateng
 * @since 2026-10-16
 */
@AutoConfiguration
@ConditionalOnClass({RedisOperations.class, RedissonClient.class})
@ConditionalOnProperty(prefix = "ateng.boot.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RedisProperties.class)
public class AtengRedisAutoConfiguration {

    private static final String SCHEMA_PREFIX = "redis://";
    private static final String SCHEMA_SSL_PREFIX = "rediss://";

    /**
     * 装配纯净无 @class 污染的安全 JSON 序列化器
     *
     * @return Redis 序列化器
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisValueSerializer")
    public RedisSerializer<Object> redisValueSerializer() {
        return RedisSerializerFactory.createJsonSerializer();
    }

    /**
     * 装配强类型 RedisTemplate，统一 Key/Value 序列化标准
     *
     * @param connectionFactory Redis 连接工厂
     * @param redisValueSerializer 值序列化器
     * @return 配置完毕的 RedisTemplate
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       @Qualifier("redisValueSerializer") RedisSerializer<Object> redisValueSerializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        template.setValueSerializer(redisValueSerializer);
        template.setHashValueSerializer(redisValueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 自动装配原生 RedissonClient，零侵入无缝继承 Spring Boot 官方 spring.data.redis.* 配置
     *
     * @param springRedisProperties Spring 官方数据源配置
     * @param atengProperties ateng-boot-redis 扩展属性
     * @param resourceLoader 资源加载器
     * @return 原生 RedissonClient 实例
     * @throws IOException 加载外部配置文件异常
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient(org.springframework.boot.autoconfigure.data.redis.RedisProperties springRedisProperties,
                                         RedisProperties atengProperties,
                                         ResourceLoader resourceLoader) throws IOException {
        Config config;

        // 1. 若显式指定了 Redisson 外部配置文件，优先从文件加载
        String configFile = atengProperties.getRedisson().getConfig();
        if (StringUtils.hasText(configFile)) {
            Resource resource = resourceLoader.getResource(configFile);
            try (InputStream inputStream = resource.getInputStream()) {
                config = Config.fromYAML(inputStream);
            }
        } else {
            // 2. 否则自动解析 Spring 官方数据源配置进行映射
            config = createRedissonConfigFromSpringProperties(springRedisProperties);
        }

        // 3. 配置线程模型
        if (atengProperties.getRedisson().getThreads() > 0) {
            config.setThreads(atengProperties.getRedisson().getThreads());
        }
        if (atengProperties.getRedisson().getNettyThreads() > 0) {
            config.setNettyThreads(atengProperties.getRedisson().getNettyThreads());
        }

        // 4. 统一 Redisson Codec 为安全纯净 JSON Codec，确保与 RedisTemplate 风格自洽
        config.setCodec(new JsonJacksonCodec(RedisSerializerFactory.createSafeObjectMapper()));

        return Redisson.create(config);
    }

    /**
     * 根据 Spring 官方 RedisProperties 映射构建 Redisson Config
     */
    Config createRedissonConfigFromSpringProperties(org.springframework.boot.autoconfigure.data.redis.RedisProperties properties) {
        Config config = new Config();
        boolean isSsl = properties.getSsl() != null && properties.getSsl().isEnabled();
        String prefix = isSsl ? SCHEMA_SSL_PREFIX : SCHEMA_PREFIX;

        // 集群模式 (Cluster)
        if (properties.getCluster() != null && !CollectionUtils.isEmpty(properties.getCluster().getNodes())) {
            ClusterServersConfig clusterServersConfig = config.useClusterServers();
            List<String> nodeAddresses = new ArrayList<>();
            for (String node : properties.getCluster().getNodes()) {
                nodeAddresses.add(formatAddress(prefix, node));
            }
            clusterServersConfig.setNodeAddresses(nodeAddresses);
            if (StringUtils.hasText(properties.getPassword())) {
                clusterServersConfig.setPassword(properties.getPassword());
            }
            if (StringUtils.hasText(properties.getUsername())) {
                clusterServersConfig.setUsername(properties.getUsername());
            }
            if (properties.getTimeout() != null) {
                clusterServersConfig.setTimeout((int) properties.getTimeout().toMillis());
            }
            if (properties.getConnectTimeout() != null) {
                clusterServersConfig.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            }
            return config;
        }

        // 哨兵模式 (Sentinel)
        if (properties.getSentinel() != null && StringUtils.hasText(properties.getSentinel().getMaster())) {
            SentinelServersConfig sentinelServersConfig = config.useSentinelServers();
            sentinelServersConfig.setMasterName(properties.getSentinel().getMaster());
            sentinelServersConfig.setDatabase(properties.getDatabase());
            for (String node : properties.getSentinel().getNodes()) {
                sentinelServersConfig.addSentinelAddress(formatAddress(prefix, node));
            }
            if (StringUtils.hasText(properties.getPassword())) {
                sentinelServersConfig.setPassword(properties.getPassword());
            }
            if (StringUtils.hasText(properties.getUsername())) {
                sentinelServersConfig.setUsername(properties.getUsername());
            }
            if (properties.getTimeout() != null) {
                sentinelServersConfig.setTimeout((int) properties.getTimeout().toMillis());
            }
            if (properties.getConnectTimeout() != null) {
                sentinelServersConfig.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            }
            return config;
        }

        // 单机模式 (Single Server, 默认兜底)
        SingleServerConfig singleServerConfig = config.useSingleServer();
        String host = StringUtils.hasText(properties.getHost()) ? properties.getHost() : "localhost";
        int port = properties.getPort() != 0 ? properties.getPort() : 6379;
        singleServerConfig.setAddress(formatAddress(prefix, host + ":" + port));
        singleServerConfig.setDatabase(properties.getDatabase());

        if (StringUtils.hasText(properties.getPassword())) {
            singleServerConfig.setPassword(properties.getPassword());
        }
        if (StringUtils.hasText(properties.getUsername())) {
            singleServerConfig.setUsername(properties.getUsername());
        }
        if (StringUtils.hasText(properties.getClientName())) {
            singleServerConfig.setClientName(properties.getClientName());
        }
        if (properties.getTimeout() != null) {
            singleServerConfig.setTimeout((int) properties.getTimeout().toMillis());
        }
        if (properties.getConnectTimeout() != null) {
            singleServerConfig.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        }

        return config;
    }

    private String formatAddress(String defaultPrefix, String address) {
        if (address.startsWith(SCHEMA_PREFIX) || address.startsWith(SCHEMA_SSL_PREFIX)) {
            return address;
        }
        return defaultPrefix + address;
    }
}
