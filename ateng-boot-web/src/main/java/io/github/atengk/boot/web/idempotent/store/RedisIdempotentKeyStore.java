package io.github.atengk.boot.web.idempotent.store;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 基于 StringRedisTemplate 的分布式接口幂等键存储实现
 * 使用 Redis SET key 1 NX EX/PX 原语保证全局原子排他抢占
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RedisIdempotentKeyStore implements IdempotentKeyStore {

    /**
     * 幂等加锁占位标识值
     */
    private static final String LOCK_VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    /**
     * 构造基于 StringRedisTemplate 的幂等存储
     *
     * @param redisTemplate Spring Redis 模板
     */
    public RedisIdempotentKeyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    }

    @Override
    public boolean tryLock(String key, long timeout, TimeUnit unit) {
        Assert.hasText(key, "idempotent key must not be blank");
        Objects.requireNonNull(unit, "time unit must not be null");
        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(key, LOCK_VALUE, timeout, unit);
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redis idempotent tryLock failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void unlock(String key) {
        Assert.hasText(key, "idempotent key must not be blank");
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redis idempotent unlock failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isLocked(String key) {
        Assert.hasText(key, "idempotent key must not be blank");
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Redis idempotent isLocked failed: " + e.getMessage(), e);
        }
    }
}
