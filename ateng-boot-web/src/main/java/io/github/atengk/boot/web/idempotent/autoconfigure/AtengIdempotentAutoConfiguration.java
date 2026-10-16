package io.github.atengk.boot.web.idempotent.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.web.idempotent.aspect.IdempotentAspect;
import io.github.atengk.boot.web.idempotent.properties.IdempotentProperties;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.InMemoryIdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.RedisIdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.RedissonIdempotentKeyStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
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
 * 接口防重幂等统一自动装配类
 * 构建 Tier 1(Redisson) -> Tier 2(Redis) -> Tier 3(InMemory) 三级自适应抢占与兜底体系
 * 在官方 Redis 与 Redisson 自动配置完成后按序装配
 *
 * @author Ateng
 * @since 2026-10-16
 */
@AutoConfiguration(afterName = {
        "org.redisson.spring.starter.RedissonAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@ConditionalOnClass({Aspect.class, ProceedingJoinPoint.class})
@EnableConfigurationProperties(IdempotentProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.web.idempotent", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({
        AtengIdempotentAutoConfiguration.RedissonIdempotentConfiguration.class,
        AtengIdempotentAutoConfiguration.RedisIdempotentConfiguration.class,
        AtengIdempotentAutoConfiguration.InMemoryIdempotentConfiguration.class
})
public class AtengIdempotentAutoConfiguration {

    /**
     * Tier 1: 基于 RedissonClient 的分布式幂等存储配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean(IdempotentKeyStore.class)
    static class RedissonIdempotentConfiguration {

        @Bean
        public IdempotentKeyStore redissonIdempotentKeyStore(RedissonClient redissonClient) {
            return new RedissonIdempotentKeyStore(redissonClient);
        }
    }

    /**
     * Tier 2: 基于 StringRedisTemplate 的分布式幂等存储配置
     * 仅在未命中 Redisson 且未自定义 IdempotentKeyStore 时生效
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(value = IdempotentKeyStore.class, type = "org.redisson.api.RedissonClient")
    static class RedisIdempotentConfiguration {

        @Bean
        public IdempotentKeyStore redisIdempotentKeyStore(StringRedisTemplate redisTemplate) {
            return new RedisIdempotentKeyStore(redisTemplate);
        }
    }

    /**
     * Tier 3: 纯 JDK 单机内存幂等存储兜底配置
     * 仅在未命中 Redisson、未命中 Redis 且未自定义 IdempotentKeyStore 时生效
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(value = IdempotentKeyStore.class, type = {
            "org.redisson.api.RedissonClient",
            "org.springframework.data.redis.core.StringRedisTemplate"
    })
    static class InMemoryIdempotentConfiguration {

        @Bean
        public IdempotentKeyStore inMemoryIdempotentKeyStore(IdempotentProperties properties) {
            return new InMemoryIdempotentKeyStore(
                    properties.getInMemory().getMaxCapacity(),
                    properties.getInMemory().getCleanInterval()
            );
        }
    }

    /**
     * 装配接口防重幂等 AOP 切面 Bean
     *
     * @param keyStore             由三级装配体系注入的幂等存储实例
     * @param properties           幂等配置属性
     * @param objectMapperProvider 可选的 Jackson ObjectMapper 提供者
     * @return 幂等切面实例
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(IdempotentKeyStore keyStore,
                                            IdempotentProperties properties,
                                            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new IdempotentAspect(keyStore, properties, objectMapperProvider);
    }
}
