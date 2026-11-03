package io.github.atengk.boot.social.autoconfigure;

import io.github.atengk.boot.social.cache.RedisSocialAuthStateCache;
import io.github.atengk.boot.social.cache.SocialAuthStateCache;
import io.github.atengk.boot.social.ticket.BindTicketStore;
import io.github.atengk.boot.social.ticket.RedisBindTicketStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 社交登录基于 Redis 的分布式状态与票据缓存自动装配
 * <p>
 * 仅当 classpath 包含 Spring Data Redis 且 Spring 容器中已注册 {@link StringRedisTemplate} 时激活。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
@ConditionalOnBean(type = "org.springframework.data.redis.core.StringRedisTemplate")
public class RedisSocialCacheConfiguration {

    @Bean
    @ConditionalOnMissingBean(SocialAuthStateCache.class)
    public SocialAuthStateCache redisSocialAuthStateCache(StringRedisTemplate stringRedisTemplate) {
        return new RedisSocialAuthStateCache(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(BindTicketStore.class)
    public BindTicketStore redisBindTicketStore(StringRedisTemplate stringRedisTemplate) {
        return new RedisBindTicketStore(stringRedisTemplate);
    }
}
