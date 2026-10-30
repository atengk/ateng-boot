package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Redis 与 Redisson 双核受管安全健康探针
 * <p>
 * 探查 Redis 连接工厂网络连通性（PING）与 Redisson 分布式客户端运行状态，
 * 继承 {@link AbstractSafeHealthIndicator} 实施异步超时切断与状态降级防卫，
 * 在 Redis 网络抖动或挂起时快速切断并发布降级事件，防止 Actuator 线程池耗尽。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
public class RedisSafeHealthIndicator extends AbstractSafeHealthIndicator {

    private final ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;
    private final ObjectProvider<RedissonClient> redissonClientProvider;

    public RedisSafeHealthIndicator(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ObjectProvider<RedissonClient> redissonClientProvider) {
        this(connectionFactoryProvider, redissonClientProvider, (Duration) null, null);
    }

    public RedisSafeHealthIndicator(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ObjectProvider<RedissonClient> redissonClientProvider,
            ObservabilityProperties properties) {
        this(connectionFactoryProvider, redissonClientProvider, properties, null);
    }

    public RedisSafeHealthIndicator(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ObjectProvider<RedissonClient> redissonClientProvider,
            ObservabilityProperties properties,
            Executor executor) {
        super(properties, executor);
        this.connectionFactoryProvider = connectionFactoryProvider;
        this.redissonClientProvider = redissonClientProvider;
    }

    public RedisSafeHealthIndicator(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ObjectProvider<RedissonClient> redissonClientProvider,
            Duration timeout,
            Executor executor) {
        super(timeout, executor);
        this.connectionFactoryProvider = connectionFactoryProvider;
        this.redissonClientProvider = redissonClientProvider;
    }

    @Override
    protected String getComponentName() {
        return "redis";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        RedisConnectionFactory connectionFactory = connectionFactoryProvider != null ? connectionFactoryProvider.getIfAvailable() : null;
        RedissonClient redissonClient = redissonClientProvider != null ? redissonClientProvider.getIfAvailable() : null;

        if (connectionFactory == null && redissonClient == null) {
            builder.unknown().withDetail("message", "未检测到已装配的 Redis 连接工厂或 Redisson 客户端");
            return;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        boolean hasError = false;

        if (connectionFactory != null) {
            RedisConnection connection = connectionFactory.getConnection();
            try {
                String ping = connection.ping();
                details.put("connectionFactory", connectionFactory.getClass().getSimpleName());
                details.put("ping", ping != null ? ping : "PONG");
            } catch (Exception ex) {
                hasError = true;
                details.put("connectionError", ex.getMessage());
                log.warn("RedisSafeHealthIndicator 连接检测异常: {}", ex.getMessage());
            } finally {
                RedisConnectionUtils.releaseConnection(connection, connectionFactory);
            }
        }

        if (redissonClient != null) {
            boolean shutdown = redissonClient.isShutdown();
            details.put("redisson", !shutdown ? "CONNECTED" : "SHUTDOWN");
            if (shutdown) {
                hasError = true;
                details.put("redissonError", "RedissonClient is shutdown");
            }
        }

        if (hasError) {
            builder.down().withDetails(details);
        } else {
            builder.up().withDetails(details);
        }
    }
}
