package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Redis 与 Redisson 双核受管安全健康探针
 * <p>
 * 探查 Redis 连接工厂网络连通性（PING）与可选 Redisson 分布式客户端运行状态，
 * 继承 {@link AbstractSafeHealthIndicator} 实施异步超时切断与状态降级防卫，
 * 在 Redis 网络抖动或挂起时快速切断并发布降级事件，防止 Actuator 线程池耗尽。
 * RedissonClient 采用弱装配与运行时类探测机制，杜绝未引入 Redisson 时触发 ClassNotFoundException。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
public class RedisSafeHealthIndicator extends AbstractSafeHealthIndicator {

    private static final String REDISSON_CLIENT_CLASS_NAME = "org.redisson.api.RedissonClient";
    private static final boolean REDISSON_PRESENT = ClassUtils.isPresent(
            REDISSON_CLIENT_CLASS_NAME,
            RedisSafeHealthIndicator.class.getClassLoader()
    );

    private final ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;
    private final ApplicationContext applicationContext;

    public RedisSafeHealthIndicator(ObjectProvider<RedisConnectionFactory> connectionFactoryProvider) {
        this(connectionFactoryProvider, (ApplicationContext) null, (Duration) null, null);
    }

    public RedisSafeHealthIndicator(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ApplicationContext applicationContext) {
        this(connectionFactoryProvider, applicationContext, (Duration) null, null);
    }

    public RedisSafeHealthIndicator(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ApplicationContext applicationContext,
            ObservabilityProperties properties) {
        this(connectionFactoryProvider, applicationContext, properties, null);
    }

    public RedisSafeHealthIndicator(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ApplicationContext applicationContext,
            ObservabilityProperties properties,
            Executor executor) {
        super(properties, executor);
        this.connectionFactoryProvider = connectionFactoryProvider;
        this.applicationContext = applicationContext;
    }

    public RedisSafeHealthIndicator(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ApplicationContext applicationContext,
            Duration timeout,
            Executor executor) {
        super(timeout, executor);
        this.connectionFactoryProvider = connectionFactoryProvider;
        this.applicationContext = applicationContext;
    }

    @Override
    protected String getComponentName() {
        return "redis";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        RedisConnectionFactory connectionFactory = connectionFactoryProvider != null ? connectionFactoryProvider.getIfAvailable() : null;
        Object redissonClient = getRedissonClient();

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
            try {
                Method isShutdownMethod = redissonClient.getClass().getMethod("isShutdown");
                boolean shutdown = (boolean) isShutdownMethod.invoke(redissonClient);
                details.put("redisson", !shutdown ? "CONNECTED" : "SHUTDOWN");
                if (shutdown) {
                    hasError = true;
                    details.put("redissonError", "RedissonClient is shutdown");
                }
            } catch (Exception ex) {
                hasError = true;
                details.put("redissonError", "RedissonClient 状态探测异常: " + ex.getMessage());
                log.warn("RedisSafeHealthIndicator RedissonClient 探查异常: {}", ex.getMessage());
            }
        }

        if (hasError) {
            builder.down().withDetails(details);
        } else {
            builder.up().withDetails(details);
        }
    }

    /**
     * 安全反射获取容器中已装配的 RedissonClient 实例
     *
     * @return RedissonClient 实例，未装配或类不存在时返回 null
     */
    private Object getRedissonClient() {
        if (!REDISSON_PRESENT || applicationContext == null) {
            return null;
        }
        try {
            Class<?> clazz = ClassUtils.forName(REDISSON_CLIENT_CLASS_NAME, getClass().getClassLoader());
            ObjectProvider<?> provider = applicationContext.getBeanProvider(clazz);
            return provider.getIfAvailable();
        } catch (Throwable ex) {
            log.debug("获取 RedissonClient 实例异常: {}", ex.getMessage());
            return null;
        }
    }
}
