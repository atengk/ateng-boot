package io.github.atengk.boot.web.ratelimit.autoconfigure;

import io.github.atengk.boot.web.ratelimit.aspect.RateLimiterAspect;
import io.github.atengk.boot.web.ratelimit.store.InMemoryRateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RedisRateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RedissonRateLimiterStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口限流防刷自动装配单元测试
 * 验证 Tier 1(Redisson) -> Tier 2(Redis) -> Tier 3(InMemory) 三级抢占与退让机制
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("接口限流防刷自动装配测试")
class AtengRateLimiterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengRateLimiterAutoConfiguration.class));

    @Test
    @DisplayName("默认无分布式中间件时成功自动装配 Tier 3 单机内存存储与切面")
    void shouldAutoConfigureInMemoryStoreAndAspectByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RateLimiterStore.class);
            assertThat(context).hasSingleBean(InMemoryRateLimiterStore.class);
            assertThat(context).doesNotHaveBean(RedissonRateLimiterStore.class);
            assertThat(context).doesNotHaveBean(RedisRateLimiterStore.class);
            assertThat(context).hasSingleBean(RateLimiterAspect.class);
        });
    }

    @Test
    @DisplayName("仅存在 StringRedisTemplate 时命中装配 Tier 2 Redis 存储")
    void shouldAutoConfigureRedisStoreWhenStringRedisTemplatePresent() {
        contextRunner.withUserConfiguration(RedisMockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiterStore.class);
                    assertThat(context).hasSingleBean(RedisRateLimiterStore.class);
                    assertThat(context).doesNotHaveBean(RedissonRateLimiterStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryRateLimiterStore.class);
                    assertThat(context).hasSingleBean(RateLimiterAspect.class);
                });
    }

    @Test
    @DisplayName("同时存在 RedissonClient 时优先命中装配 Tier 1 Redisson 存储")
    void shouldPrioritizeRedissonStoreWhenBothPresent() {
        contextRunner.withUserConfiguration(RedissonAndRedisMockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiterStore.class);
                    assertThat(context).hasSingleBean(RedissonRateLimiterStore.class);
                    assertThat(context).doesNotHaveBean(RedisRateLimiterStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryRateLimiterStore.class);
                    assertThat(context).hasSingleBean(RateLimiterAspect.class);
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.ratelimit.enabled=false 时关闭限流模块装配")
    void shouldDisableAutoConfigurationWhenPropertyFalse() {
        contextRunner.withPropertyValues("ateng.boot.web.ratelimit.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RateLimiterStore.class);
                    assertThat(context).doesNotHaveBean(RateLimiterAspect.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisMockConfig {
        @Bean
        public StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedissonAndRedisMockConfig {
        @Bean
        public RedissonClient redissonClient() {
            return Mockito.mock(RedissonClient.class);
        }

        @Bean
        public StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }
    }
}
