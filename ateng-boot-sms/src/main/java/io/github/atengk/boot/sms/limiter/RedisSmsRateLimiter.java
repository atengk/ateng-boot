package io.github.atengk.boot.sms.limiter;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.properties.SmsRateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis ZSet 滑动窗口的分布式短信频控限流器
 * 当 Spring 容器中检测到 StringRedisTemplate 时自动生效
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class RedisSmsRateLimiter implements SmsRateLimiter {

    private static final String KEY_PREFIX = "ateng:sms:rate:";

    private static final String SLIDING_WINDOW_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local oneMinuteAgo = tonumber(ARGV[2])
            local oneHourAgo = tonumber(ARGV[3])
            local oneDayAgo = tonumber(ARGV[4])
            local perMinute = tonumber(ARGV[5])
            local perHour = tonumber(ARGV[6])
            local perDay = tonumber(ARGV[7])
            local memberId = ARGV[8]

            -- 1. 清理超过 1 天的旧数据
            redis.call('ZREMRANGEBYSCORE', key, 0, oneDayAgo)

            -- 2. 统计各窗口发送次数
            local dayCount = redis.call('ZCARD', key)
            if perDay > 0 and dayCount >= perDay then
                return -3
            end

            local hourCount = redis.call('ZCOUNT', key, oneHourAgo, '+inf')
            if perHour > 0 and hourCount >= perHour then
                return -2
            end

            local minuteCount = redis.call('ZCOUNT', key, oneMinuteAgo, '+inf')
            if perMinute > 0 and minuteCount >= perMinute then
                return -1
            end

            -- 3. 未超限，记录当前时间戳并刷新 key 过期时间为 1 天
            redis.call('ZADD', key, now, memberId)
            redis.call('EXPIRE', key, 86400)
            return 1
            """;

    private final StringRedisTemplate redisTemplate;
    private final SmsRateLimitProperties properties;
    private final DefaultRedisScript<Long> redisScript;

    public RedisSmsRateLimiter(StringRedisTemplate redisTemplate, SmsRateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties != null ? properties : new SmsRateLimitProperties();
        this.redisScript = new DefaultRedisScript<>();
        this.redisScript.setScriptText(SLIDING_WINDOW_LUA);
        this.redisScript.setResultType(Long.class);
    }

    @Override
    public boolean tryAcquire(String phone) {
        try {
            acquire(phone);
            return true;
        } catch (SmsRateLimitException e) {
            return false;
        }
    }

    @Override
    public void acquire(String phone) {
        BusinessAssert.hasText(phone, "目标手机号不能为空");

        long now = Instant.now().toEpochMilli();
        long oneMinuteAgo = now - 60_000L;
        long oneHourAgo = now - 3_600_000L;
        long oneDayAgo = now - 86_400_000L;

        String key = KEY_PREFIX + phone;
        String memberId = now + ":" + UUID.randomUUID().toString().substring(0, 8);

        try {
            Long result = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(oneMinuteAgo),
                    String.valueOf(oneHourAgo),
                    String.valueOf(oneDayAgo),
                    String.valueOf(properties.getPerMinute()),
                    String.valueOf(properties.getPerHour()),
                    String.valueOf(properties.getPerDay()),
                    memberId
            );

            if (result == null || result == 1L) {
                return;
            }

            if (result == -1L) {
                throw new SmsRateLimitException("短信发送过于频繁，请等待 60 秒后再试", 60L);
            } else if (result == -2L) {
                throw new SmsRateLimitException("短信发送过于频繁，1小时内发送次数已达上限", 3600L);
            } else if (result == -3L) {
                throw new SmsRateLimitException("今日短信发送次数已达上限，请明天再试", 86400L);
            }
        } catch (SmsRateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[RedisSmsRateLimiter] Redis 频控判定通信异常，降级放行: {}", e.getMessage());
        }
    }

    @Override
    public void reset(String phone) {
        if (phone != null) {
            try {
                redisTemplate.delete(KEY_PREFIX + phone);
            } catch (Exception e) {
                log.warn("[RedisSmsRateLimiter] 重置频控记录失败: {}", e.getMessage());
            }
        }
    }
}
