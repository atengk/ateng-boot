package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailRateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 {@link StringRedisTemplate} 与原子 Lua 脚本的分布式邮件出站频率限制实现
 * <p>
 * 结合账号维度令牌桶脚本（{@code HMSET}）与单收件人维度滑动窗口脚本（{@code ZSET}），
 * 并在 Redis 网络或集群抖动时依据 {@code failOpenOnRedisError} 执行高可用降级放行或因果异常快速失败。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Slf4j
public class RedisMailRateLimiter implements MailRateLimiter {

    private static final String ACCOUNT_TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local max_tokens = tonumber(ARGV[1])
            local refill_per_sec = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local ttl_ms = tonumber(ARGV[5])

            if requested > max_tokens then
                return 0
            end

            local info = redis.call('HMGET', key, 'tokens', 'last_time')
            local tokens = tonumber(info[1])
            local last_time = tonumber(info[2])

            if not tokens or not last_time then
                tokens = max_tokens
                last_time = now
            else
                local elapsed = math.max(0, now - last_time)
                if elapsed > 0 then
                    local generated = (elapsed / 1000.0) * refill_per_sec
                    tokens = math.min(max_tokens, tokens + generated)
                    last_time = now
                end
            end

            if tokens >= requested then
                tokens = tokens - requested
                redis.call('HMSET', key, 'tokens', tokens, 'last_time', last_time)
                redis.call('PEXPIRE', key, ttl_ms)
                return 1
            else
                redis.call('HMSET', key, 'tokens', tokens, 'last_time', last_time)
                redis.call('PEXPIRE', key, ttl_ms)
                return 0
            end
            """;

    private static final String RECIPIENT_SLIDING_WINDOW_LUA = """
            local key = KEYS[1]
            local window_ms = tonumber(ARGV[1])
            local max_count = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local member = ARGV[4]

            local min_score = now - window_ms
            if member == '' then
                local active = redis.call('ZCOUNT', key, min_score, '+inf')
                if active + 1 > max_count then
                    return 0
                end
                return 1
            end

            redis.call('ZREMRANGEBYSCORE', key, '-inf', min_score)
            local current = redis.call('ZCARD', key)
            if current + 1 > max_count then
                redis.call('PEXPIRE', key, window_ms)
                return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window_ms)
            return 1
            """;

    private final StringRedisTemplate redisTemplate;
    private final MailRateLimitProperties properties;
    private final RedisScript<Long> accountTokenBucketScript;
    private final RedisScript<Long> recipientSlidingWindowScript;
    private final String instanceNodeId = IdUtil.fastSimpleUUID().substring(0, 8);
    private final AtomicLong memberSequence = new AtomicLong(0L);

    public RedisMailRateLimiter(StringRedisTemplate redisTemplate, MailRateLimitProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = properties != null ? properties : new MailRateLimitProperties();
        this.accountTokenBucketScript = new DefaultRedisScript<>(ACCOUNT_TOKEN_BUCKET_LUA, Long.class);
        this.recipientSlidingWindowScript = new DefaultRedisScript<>(RECIPIENT_SLIDING_WINDOW_LUA, Long.class);
    }

    @Override
    public boolean tryAcquire(MailAccount account, MailMessage message, Collection<String> deduplicatedRecipients) {
        if (!properties.isEnabled()) {
            return true;
        }
        if (deduplicatedRecipients == null || deduplicatedRecipients.isEmpty()) {
            return true;
        }
        int recipientCount = deduplicatedRecipients.size();
        long now = System.currentTimeMillis();

        try {
            long windowMs = Math.max(1L, properties.getRecipientWindow().toMillis());
            long maxCount = Math.max(1L, properties.getRecipientMaxCount());

            // 1. 若开启单收件人防轰炸且同时存在多个收件人或账号限流，先只读预检所有收件人余量防止半扣减污染
            if (properties.isRecipientEnabled() && (properties.isAccountEnabled() || deduplicatedRecipients.size() > 1)) {
                for (String recipient : deduplicatedRecipients) {
                    String normalized = MailGuardSupport.normalizeRecipient(recipient);
                    if (normalized.isBlank()) {
                        continue;
                    }
                    String recipientKey = properties.getRedisKeyPrefix() + "rcpt:" + normalized;
                    Long preCheck = redisTemplate.execute(
                            recipientSlidingWindowScript,
                            Collections.singletonList(recipientKey),
                            String.valueOf(windowMs),
                            String.valueOf(maxCount),
                            String.valueOf(now),
                            ""
                    );
                    if (!Long.valueOf(1L).equals(preCheck)) {
                        return false;
                    }
                }
            }

            // 2. 发信账号维度分布式令牌桶校验与扣减
            if (properties.isAccountEnabled()) {
                long capacity = Math.max(1L, properties.getAccountCapacity());
                double rate = Math.max(0.0001d, properties.getAccountPermitsPerSecond());
                long ttlMs = Math.max(60_000L, (long) Math.ceil((capacity / rate) * 2000.0d));
                String accountKey = properties.getRedisKeyPrefix() + "acct:" + MailGuardSupport.resolveAccountKey(account);

                Long allowed = redisTemplate.execute(
                        accountTokenBucketScript,
                        Collections.singletonList(accountKey),
                        String.valueOf(capacity),
                        String.valueOf(rate),
                        String.valueOf(now),
                        String.valueOf(recipientCount),
                        String.valueOf(ttlMs)
                );
                if (!Long.valueOf(1L).equals(allowed)) {
                    return false;
                }
            }

            // 3. 单收件人维度分布式滑动窗口原子准入与递增
            if (properties.isRecipientEnabled()) {
                for (String recipient : deduplicatedRecipients) {
                    String normalized = MailGuardSupport.normalizeRecipient(recipient);
                    if (normalized.isBlank()) {
                        continue;
                    }
                    String recipientKey = properties.getRedisKeyPrefix() + "rcpt:" + normalized;
                    String uniqueMember = now + "-" + instanceNodeId + "-" + memberSequence.incrementAndGet();

                    Long allowed = redisTemplate.execute(
                            recipientSlidingWindowScript,
                            Collections.singletonList(recipientKey),
                            String.valueOf(windowMs),
                            String.valueOf(maxCount),
                            String.valueOf(now),
                            uniqueMember
                    );
                    if (!Long.valueOf(1L).equals(allowed)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            if (properties.isFailOpenOnRedisError()) {
                log.warn("[MailRateLimiter] Redis 频控执行异常，已按 fail-open 降级放行，原因: {}", e.getMessage(), e);
                return true;
            }
            throw new MailSendException(MailResultCode.MAIL_SEND_ERROR,
                    "Redis 邮件出站频控执行异常: " + e.getMessage(), e);
        }
    }
}
