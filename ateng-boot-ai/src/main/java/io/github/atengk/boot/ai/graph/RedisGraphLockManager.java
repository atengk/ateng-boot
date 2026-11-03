package io.github.atengk.boot.ai.graph;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.core.util.IdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;

/**
 * 基于 Redis 的分布式租约锁管理器（带 Lua 高熵令牌原子防卫）
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class RedisGraphLockManager implements GraphLockManager {

    private static final Logger log = LoggerFactory.getLogger(RedisGraphLockManager.class);

    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisGraphLockManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    }

    @Override
    public String acquireLock(String threadId, Duration ttl, String lockPrefix) {
        String token = IdUtil.fastSimpleUUID();
        String lockKey = lockPrefix + threadId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new AiException(AiResultCode.AI_GRAPH_LOCK_CONFLICT,
                    "认知图会话 [" + threadId + "] 当前正在被其他审批操作并发处理中，请勿重复提交");
        }
        return token;
    }

    @Override
    public void releaseLock(String threadId, String lockToken, String lockPrefix) {
        if (lockToken == null) {
            return;
        }
        String lockKey = lockPrefix + threadId;
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockToken);
        } catch (Exception e) {
            log.warn("释放 Redis 锁异常 [key={}, token={}]: {}", lockKey, lockToken, e.getMessage());
        }
    }
}
