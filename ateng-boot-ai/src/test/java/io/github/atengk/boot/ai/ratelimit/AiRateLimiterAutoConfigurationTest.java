package io.github.atengk.boot.ai.ratelimit;

import io.github.atengk.boot.ai.autoconfigure.AtengAiAutoConfiguration;
import io.github.atengk.boot.ai.template.AiTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AI 微观突发流量滑动窗口限流器自动装配集成测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class AiRateLimiterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAiAutoConfiguration.class));

    @Test
    @DisplayName("默认单机环境自动装配单机并发无锁限流器 DefaultAiRateLimiter")
    void testInMemoryRateLimiterAutoConfigured() {
        contextRunner
                .withPropertyValues("ateng.boot.ai.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AiRateLimiter.class);
                    AiRateLimiter limiter = context.getBean(AiRateLimiter.class);
                    assertThat(limiter).isInstanceOf(DefaultAiRateLimiter.class);
                    assertThat(limiter.isDistributed()).isFalse();

                    AiTemplate template = context.getBean(AiTemplate.class);
                    assertThat(template.getRateLimiter()).isSameAs(limiter);
                });
    }

    @Test
    @DisplayName("存在 StringRedisTemplate 时自动装配 Redis 分布式限流器 RedisAiRateLimiter")
    void testRedisRateLimiterAutoConfiguredWhenRedisPresent() {
        contextRunner
                .withUserConfiguration(MockRedisConfiguration.class)
                .withPropertyValues("ateng.boot.ai.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AiRateLimiter.class);
                    AiRateLimiter limiter = context.getBean(AiRateLimiter.class);
                    assertThat(limiter).isInstanceOf(RedisAiRateLimiter.class);
                    assertThat(limiter.isDistributed()).isTrue();

                    AiTemplate template = context.getBean(AiTemplate.class);
                    assertThat(template.getRateLimiter()).isSameAs(limiter);
                });
    }

    @Test
    @DisplayName("显式配置 ateng.boot.ai.rate-limit.enabled=false 时不装配限流器")
    void testRateLimiterDisabledWhenConfiguredFalse() {
        contextRunner
                .withPropertyValues("ateng.boot.ai.rate-limit.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AiRateLimiter.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockRedisConfiguration {
        @Bean
        public StringRedisTemplate stringRedisTemplate() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
            return template;
        }
    }
}
