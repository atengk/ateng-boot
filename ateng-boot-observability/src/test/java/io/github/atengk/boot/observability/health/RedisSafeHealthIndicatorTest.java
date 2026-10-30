package io.github.atengk.boot.observability.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RedisSafeHealthIndicator 单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("RedisSafeHealthIndicator Redis 与 Redisson 双核安全健康探针单元测试")
class RedisSafeHealthIndicatorTest {

    @Test
    @DisplayName("未装配任何 Redis 或 Redisson 实例时，返回 UNKNOWN 状态")
    void testNoRedisConfigured() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                beanFactory.getBeanProvider(RedisConnectionFactory.class),
                beanFactory.getBeanProvider(RedissonClient.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("message", "未检测到已装配的 Redis 连接工厂或 Redisson 客户端");
    }

    @Test
    @DisplayName("Redis 连接工厂与 Redisson 客户端均正常，返回 UP 状态")
    void testRedisAndRedissonUp() {
        RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
        RedisConnection mockConnection = mock(RedisConnection.class);
        when(mockFactory.getConnection()).thenReturn(mockConnection);
        when(mockConnection.ping()).thenReturn("PONG");

        RedissonClient mockRedisson = mock(RedissonClient.class);
        when(mockRedisson.isShutdown()).thenReturn(false);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("redisConnectionFactory", mockFactory);
        beanFactory.addBean("redissonClient", mockRedisson);

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                beanFactory.getBeanProvider(RedisConnectionFactory.class),
                beanFactory.getBeanProvider(RedissonClient.class),
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("ping", "PONG");
        assertThat(health.getDetails()).containsEntry("redisson", "CONNECTED");
    }

    @Test
    @DisplayName("Redis 连接探测抛出异常时，返回 DOWN 状态")
    void testRedisConnectionDown() {
        RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
        when(mockFactory.getConnection()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("redisConnectionFactory", mockFactory);

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                beanFactory.getBeanProvider(RedisConnectionFactory.class),
                beanFactory.getBeanProvider(RedissonClient.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }

    @Test
    @DisplayName("Redisson 客户端处于 shutdown 状态时，返回 DOWN 状态")
    void testRedissonShutdownDown() {
        RedissonClient mockRedisson = mock(RedissonClient.class);
        when(mockRedisson.isShutdown()).thenReturn(true);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("redissonClient", mockRedisson);

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                beanFactory.getBeanProvider(RedisConnectionFactory.class),
                beanFactory.getBeanProvider(RedissonClient.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("redisson", "SHUTDOWN");
        assertThat(health.getDetails()).containsEntry("redissonError", "RedissonClient is shutdown");
    }

    @Test
    @DisplayName("超时防假死熔断：探测挂起超过阈值时，切断并降级为 UNKNOWN 状态")
    void testTimeoutDegradation() {
        RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
        RedisConnection mockConnection = mock(RedisConnection.class);
        when(mockFactory.getConnection()).thenReturn(mockConnection);
        when(mockConnection.ping()).thenAnswer(invocation -> {
            Thread.sleep(500);
            return "PONG";
        });

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("redisConnectionFactory", mockFactory);

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                beanFactory.getBeanProvider(RedisConnectionFactory.class),
                beanFactory.getBeanProvider(RedissonClient.class),
                Duration.ofMillis(80),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("status", "TIMEOUT");
    }
}
