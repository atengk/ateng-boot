package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailQuotaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 基于 {@link StringRedisTemplate} 与原子 Lua 脚本的分布式自然日总配额硬熔断实现
 * <p>
 * 依托 Redis 原子 {@code INCRBY} 前置上限判定与自然日键隔离，确保集群多节点共享精确的单日发送配额，
 * 并在 Redis 不可用时遵循 {@code failOpenOnRedisError} 执行高可用容错。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Slf4j
public class RedisMailDailyQuotaBreaker implements MailDailyQuotaBreaker {

    private static final String DAILY_QUOTA_LUA = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local delta = tonumber(ARGV[2])
            local ttl_ms = tonumber(ARGV[3])

            local current = tonumber(redis.call('GET', key) or '0')
            if current + delta > limit then
                return {0, current}
            end
            local updated = redis.call('INCRBY', key, delta)
            redis.call('PEXPIRE', key, ttl_ms)
            return {1, updated}
            """;

    private final StringRedisTemplate redisTemplate;
    private final MailQuotaProperties properties;
    private final Clock clock;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> quotaScript;

    public RedisMailDailyQuotaBreaker(StringRedisTemplate redisTemplate, MailQuotaProperties properties) {
        this(redisTemplate, properties, Clock.systemDefaultZone());
    }

    public RedisMailDailyQuotaBreaker(StringRedisTemplate redisTemplate, MailQuotaProperties properties, Clock clock) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = properties != null ? properties : new MailQuotaProperties();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.quotaScript = new DefaultRedisScript<>(DAILY_QUOTA_LUA, List.class);
    }

    @Override
    public QuotaCheckResult tryConsume(MailAccount account, MailMessage message, int recipientCount) {
        if (recipientCount <= 0) {
            throw new IllegalArgumentException("recipientCount 必须大于或等于 1: " + recipientCount);
        }
        LocalDate today = LocalDate.now(clock);
        long dailyLimit = properties.getDailyMaxCount();
        if (!properties.isEnabled()) {
            return new QuotaCheckResult(true, today, dailyLimit, getCurrentCount(today), recipientCount);
        }

        String redisKey = buildDailyKey(today);
        long ttlMs = Math.max(60_000L, properties.getRedisKeyTtl().toMillis());
        try {
            List<?> rawResult = redisTemplate.execute(
                    quotaScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(dailyLimit),
                    String.valueOf(recipientCount),
                    String.valueOf(ttlMs)
            );
            if (rawResult == null || rawResult.size() < 2) {
                return handleRedisErrorFallback(today, dailyLimit, recipientCount,
                        new IllegalStateException("Redis 日配额脚本返回异常结果: " + rawResult));
            }
            long allowedFlag = ((Number) rawResult.get(0)).longValue();
            long countValue = ((Number) rawResult.get(1)).longValue();
            return new QuotaCheckResult(allowedFlag == 1L, today, dailyLimit, countValue, recipientCount);
        } catch (Exception e) {
            return handleRedisErrorFallback(today, dailyLimit, recipientCount, e);
        }
    }

    @Override
    public long getCurrentCount(LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now(clock);
        try {
            String value = redisTemplate.opsForValue().get(buildDailyKey(target));
            return (value != null && !value.isBlank()) ? Long.parseLong(value.trim()) : 0L;
        } catch (Exception e) {
            log.warn("[MailDailyQuotaBreaker] 读取 Redis 日配额计数失败，降级返回 0，原因: {}", e.getMessage(), e);
            return 0L;
        }
    }

    @Override
    public void reset() {
        try {
            redisTemplate.delete(buildDailyKey(LocalDate.now(clock)));
        } catch (Exception e) {
            log.warn("[MailDailyQuotaBreaker] 重置 Redis 日配额键失败，原因: {}", e.getMessage(), e);
        }
    }

    private QuotaCheckResult handleRedisErrorFallback(LocalDate today, long dailyLimit, int recipientCount, Exception cause) {
        if (properties.isFailOpenOnRedisError()) {
            log.warn("[MailDailyQuotaBreaker] Redis 日配额检查异常，已按 fail-open 降级放行，原因: {}", cause.getMessage(), cause);
            return new QuotaCheckResult(true, today, dailyLimit, 0L, recipientCount);
        }
        throw new MailSendException(MailResultCode.MAIL_SEND_ERROR,
                "Redis 邮件自然日配额服务异常: " + cause.getMessage(), cause);
    }

    private String buildDailyKey(LocalDate date) {
        return properties.getRedisKeyPrefix() + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
