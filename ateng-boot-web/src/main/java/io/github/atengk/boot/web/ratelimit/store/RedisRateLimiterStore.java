package io.github.atengk.boot.web.ratelimit.store;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 基于 StringRedisTemplate 与原子令牌桶 Lua 脚本的分布式接口限流存储实现
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RedisRateLimiterStore implements RateLimiterStore {

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local max_tokens = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local rate_limit_info = redis.call('HMGET', key, 'tokens', 'last_time')
            local current_tokens = tonumber(rate_limit_info[1])
            local last_time = tonumber(rate_limit_info[2])

            if not current_tokens then
                current_tokens = max_tokens
                last_time = now
            else
                local elapsed = math.max(0, now - last_time)
                if elapsed > 0 then
                    local generated = elapsed * (max_tokens / window_ms)
                    current_tokens = math.min(max_tokens, current_tokens + generated)
                    last_time = now
                end
            end

            if current_tokens >= requested then
                current_tokens = current_tokens - requested
                redis.call('HMSET', key, 'tokens', current_tokens, 'last_time', last_time)
                redis.call('PEXPIRE', key, math.floor(window_ms * 2))
                return 1
            else
                redis.call('HMSET', key, 'tokens', current_tokens, 'last_time', last_time)
                redis.call('PEXPIRE', key, math.floor(window_ms * 2))
                return 0
            end
            """;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> script;

    public RedisRateLimiterStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
    }

    @Override
    public boolean tryAcquire(String key, long count, long time, TimeUnit unit) {
        Assert.hasText(key, "rate limiter key must not be blank");
        Objects.requireNonNull(unit, "time unit must not be null");
        if (count <= 0 || time <= 0) {
            return true;
        }

        long windowMillis = unit.toMillis(time);
        long now = System.currentTimeMillis();

        Long result = redisTemplate.execute(
                script,
                Collections.singletonList(key),
                String.valueOf(count),
                String.valueOf(windowMillis),
                String.valueOf(now),
                "1"
        );
        return result != null && result == 1L;
    }
}
