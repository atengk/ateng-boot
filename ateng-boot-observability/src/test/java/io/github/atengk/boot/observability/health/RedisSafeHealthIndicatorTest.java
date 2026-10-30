package io.github.atengk.boot.observability.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.support.StaticApplicationContext;
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
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                context.getBeanProvider(RedisConnectionFactory.class),
                context
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

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean("redisConnectionFactory", RedisConnectionFactory.class, () -> mockFactory);
        context.registerBean("redissonClient", RedissonClient.class, () -> mockRedisson);
        context.refresh();

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                context.getBeanProvider(RedisConnectionFactory.class),
                context,
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("ping", "PONG");
        assertThat(health.getDetails()).containsEntry("redisson", "CONNECTED");
    }

    @Test
    @DisplayName("仅装配 Redis 连接工厂且未装配 Redisson 客户端时，正常返回 UP 状态")
    void testOnlyRedisUp() {
        RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
        RedisConnection mockConnection = mock(RedisConnection.class);
        when(mockFactory.getConnection()).thenReturn(mockConnection);
        when(mockConnection.ping()).thenReturn("PONG");

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean("redisConnectionFactory", RedisConnectionFactory.class, () -> mockFactory);
        context.refresh();

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                context.getBeanProvider(RedisConnectionFactory.class),
                context
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("ping", "PONG");
        assertThat(health.getDetails()).doesNotContainKey("redisson");
    }

    @Test
    @DisplayName("Redis 连接探测抛出异常时，返回 DOWN 状态")
    void testRedisConnectionDown() {
        RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
        when(mockFactory.getConnection()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean("redisConnectionFactory", RedisConnectionFactory.class, () -> mockFactory);
        context.refresh();

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                context.getBeanProvider(RedisConnectionFactory.class),
                context
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

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean("redissonClient", RedissonClient.class, () -> mockRedisson);
        context.refresh();

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                context.getBeanProvider(RedisConnectionFactory.class),
                context
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

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean("redisConnectionFactory", RedisConnectionFactory.class, () -> mockFactory);
        context.refresh();

        RedisSafeHealthIndicator indicator = new RedisSafeHealthIndicator(
                context.getBeanProvider(RedisConnectionFactory.class),
                context,
                Duration.ofMillis(80),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("status", "TIMEOUT");
    }
}
