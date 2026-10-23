package io.github.atengk.boot.pay.core.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;

/**
 * 基于 Redis 的分布式防重锁实现
 * <p>
 * 在微服务与多实例集群部署环境下，依托 Redis 分布式原子命令 {@code SETNX}
 * 建立跨实例防重防重放屏障，杜绝网络抖动下多节点重复执行业务记账。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class RedisPayLockManager implements PayLockManager {

    private static final Logger log = LoggerFactory.getLogger(RedisPayLockManager.class);
    private static final String LOCK_PREFIX = "ateng:pay:lock:";
    private static final String LOCK_VALUE = "LOCKED";

    private final StringRedisTemplate redisTemplate;

    public RedisPayLockManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate 不能为空");
    }

    @Override
    public boolean tryLock(String lockKey, Duration expireTime) {
        Objects.requireNonNull(lockKey, "lockKey 不能为空");
        Duration duration = expireTime != null ? expireTime : Duration.ofSeconds(10);
        String realKey = LOCK_PREFIX + lockKey;

        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(realKey, LOCK_VALUE, duration);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Failed to acquire Redis pay lock for [{}], fallback to false: {}", lockKey, e.getMessage());
            return false;
        }
    }

    @Override
    public void unlock(String lockKey) {
        if (lockKey == null) {
            return;
        }
        String realKey = LOCK_PREFIX + lockKey;
        try {
            redisTemplate.delete(realKey);
        } catch (Exception e) {
            log.warn("Failed to release Redis pay lock for [{}]: {}", lockKey, e.getMessage());
        }
    }
}
