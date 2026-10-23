package io.github.atengk.boot.pay.core.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.*;

/**
 * 基于 Redis 的分布式防重锁实现
 * <p>
 * 在微服务与多实例集群部署环境下，依托 Redis 分布式原子命令 {@code SETNX}
 * 建立跨实例防重防重放屏障，杜绝网络抖动下多节点重复执行业务记账。
 * 遵循严格的防御性锁释放范式：通过 ThreadLocal 记录每个锁键的唯一持有令牌，
 * 在释放锁时采用原子 Lua 脚本校验令牌匹配性，杜绝因业务耗时超出锁租期而误删后继线程获取的新锁。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class RedisPayLockManager implements PayLockManager {

    private static final Logger log = LoggerFactory.getLogger(RedisPayLockManager.class);
    private static final String LOCK_PREFIX = "ateng:pay:lock:";
    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> unlockScript;
    private final ThreadLocal<Map<String, String>> threadLocks = ThreadLocal.withInitial(HashMap::new);

    public RedisPayLockManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate 不能为空");
        this.unlockScript = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
    }

    @Override
    public boolean tryLock(String lockKey, Duration expireTime) {
        Objects.requireNonNull(lockKey, "lockKey 不能为空");
        Duration duration = expireTime != null ? expireTime : Duration.ofSeconds(10);
        String realKey = LOCK_PREFIX + lockKey;
        String token = UUID.randomUUID().toString();

        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(realKey, token, duration);
            if (Boolean.TRUE.equals(acquired)) {
                threadLocks.get().put(lockKey, token);
                return true;
            }
            return false;
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
        Map<String, String> locks = threadLocks.get();
        String token = locks.remove(lockKey);
        if (locks.isEmpty()) {
            threadLocks.remove();
        }
        if (token == null) {
            // 当前线程未持有该锁（未抢到锁或锁超时未决），防御性拦截，严禁误删其他线程锁
            return;
        }

        String realKey = LOCK_PREFIX + lockKey;
        try {
            redisTemplate.execute(unlockScript, Collections.singletonList(realKey), token);
        } catch (Exception e) {
            log.warn("Failed to release Redis pay lock for [{}]: {}", lockKey, e.getMessage());
        }
    }
}
