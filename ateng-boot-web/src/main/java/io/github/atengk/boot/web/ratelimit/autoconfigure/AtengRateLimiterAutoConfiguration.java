package io.github.atengk.boot.web.ratelimit.autoconfigure;

import io.github.atengk.boot.web.ratelimit.aspect.RateLimiterAspect;
import io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties;
import io.github.atengk.boot.web.ratelimit.store.InMemoryRateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RedisRateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RedissonRateLimiterStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 接口限流防刷统一自动装配类
 * 构建 Tier 1(Redisson) -> Tier 2(Redis) -> Tier 3(InMemory) 三级自适应令牌桶体系
 *
 * @author Ateng
 * @since 2026-10-16
 */
@AutoConfiguration(afterName = {
        "org.redisson.spring.starter.RedissonAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@ConditionalOnClass({Aspect.class, ProceedingJoinPoint.class})
@EnableConfigurationProperties(RateLimiterProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.web.ratelimit", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({
        AtengRateLimiterAutoConfiguration.RedissonRateLimiterConfiguration.class,
        AtengRateLimiterAutoConfiguration.RedisRateLimiterConfiguration.class,
        AtengRateLimiterAutoConfiguration.InMemoryRateLimiterConfiguration.class
})
public class AtengRateLimiterAutoConfiguration {

    /**
     * Tier 1: 基于 RedissonClient 的分布式令牌桶限流存储配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean(RateLimiterStore.class)
    static class RedissonRateLimiterConfiguration {

        @Bean
        public RateLimiterStore redissonRateLimiterStore(RedissonClient redissonClient) {
            return new RedissonRateLimiterStore(redissonClient);
        }
    }

    /**
     * Tier 2: 基于 StringRedisTemplate 的分布式令牌桶限流存储配置
     * 仅在未命中 Redisson 且未自定义 RateLimiterStore 时生效
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(value = RateLimiterStore.class, type = "org.redisson.api.RedissonClient")
    static class RedisRateLimiterConfiguration {

        @Bean
        public RateLimiterStore redisRateLimiterStore(StringRedisTemplate redisTemplate) {
            return new RedisRateLimiterStore(redisTemplate);
        }
    }

    /**
     * Tier 3: 纯 JDK 单机内存令牌桶限流存储兜底配置
     * 仅在未命中 Redisson、未命中 Redis 且未自定义 RateLimiterStore 时生效
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(value = RateLimiterStore.class, type = {
            "org.redisson.api.RedissonClient",
            "org.springframework.data.redis.core.StringRedisTemplate"
    })
    static class InMemoryRateLimiterConfiguration {

        @Bean
        public RateLimiterStore inMemoryRateLimiterStore(RateLimiterProperties properties) {
            return new InMemoryRateLimiterStore(
                    properties.getInMemory().getMaxCapacity(),
                    properties.getInMemory().getCleanInterval()
            );
        }
    }

    /**
     * 装配接口限流 AOP 切面 Bean
     *
     * @param rateLimiterStore 限流存储引擎实例
     * @return 限流切面实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterAspect rateLimiterAspect(RateLimiterStore rateLimiterStore) {
        return new RateLimiterAspect(rateLimiterStore);
    }
}
