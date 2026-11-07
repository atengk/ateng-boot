package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.core.util.BusinessAssert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 基于 Redis 的分布式短信出站发信幂等防重卫士实现
 * 依托 Redis SETNX 与 TTL 保障微服务集群多节点并发发信的绝对幂等
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
public class RedisSmsIdempotentGuard implements SmsIdempotentGuard {

    private static final String REDIS_PREFIX = "ateng:sms:idempotent:";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisSmsIdempotentGuard(StringRedisTemplate stringRedisTemplate) {
        BusinessAssert.notNull(stringRedisTemplate, "StringRedisTemplate 不能为空");
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean acquireLock(String key, Duration window) {
        if (key == null || key.isBlank()) {
            return true;
        }
        try {
            Duration ttl = (window != null && !window.isNegative() && !window.isZero()) ? window : Duration.ofMinutes(3);
            Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(REDIS_PREFIX + key.trim(), "1", ttl);
            return Boolean.TRUE.equals(success);
        } catch (Exception ex) {
            log.error("[RedisSmsIdempotentGuard] Redis 幂等加锁异常，保底放行: {}", ex.getMessage());
            return true;
        }
    }

    @Override
    public void releaseLock(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.delete(REDIS_PREFIX + key.trim());
        } catch (Exception ex) {
            log.error("[RedisSmsIdempotentGuard] Redis 释放幂等锁异常: {}", ex.getMessage());
        }
    }
}
