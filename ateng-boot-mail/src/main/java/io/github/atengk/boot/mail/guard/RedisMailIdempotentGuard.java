package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailIdempotentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;

/**
 * 基于 {@link StringRedisTemplate} {@code SET key 1 NX PX} 的分布式邮件出站幂等防重守卫实现
 * <p>
 * 支持跨节点唯一抢占占位，并在物理发信失败或前置阻断时主动删除幂等锁以恢复补偿重试，
 * 异常路径遵循 {@code failOpenOnRedisError} 执行降级放行或严格阻断。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Slf4j
public class RedisMailIdempotentGuard implements MailIdempotentGuard {

    private static final String LOCK_VALUE = "1";

    private final StringRedisTemplate redisTemplate;
    private final MailIdempotentProperties properties;

    public RedisMailIdempotentGuard(StringRedisTemplate redisTemplate, MailIdempotentProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = properties != null ? properties : new MailIdempotentProperties();
    }

    @Override
    public boolean tryAcquire(MailAccount account, MailMessage message, String idempotentKey) {
        if (!properties.isEnabled()) {
            return true;
        }
        if (idempotentKey == null || idempotentKey.isBlank()) {
            return true;
        }
        String fullKey = buildFullKey(idempotentKey);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(fullKey, LOCK_VALUE, properties.getTtl());
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            if (properties.isFailOpenOnRedisError()) {
                log.warn("[MailIdempotentGuard] Redis 幂等抢占异常，已按 fail-open 降级放行，key={}, 原因: {}",
                        idempotentKey, e.getMessage(), e);
                return true;
            }
            throw new MailSendException(MailResultCode.MAIL_SEND_ERROR,
                    "Redis 邮件幂等防重服务异常: " + e.getMessage(), e);
        }
    }

    @Override
    public void releaseLock(MailAccount account, MailMessage message, String idempotentKey) {
        if (idempotentKey == null || idempotentKey.isBlank()) {
            return;
        }
        String fullKey = buildFullKey(idempotentKey);
        try {
            redisTemplate.delete(fullKey);
        } catch (Exception e) {
            log.warn("[MailIdempotentGuard] Redis 释放幂等锁失败，key={}, 原因: {}", idempotentKey, e.getMessage(), e);
        }
    }

    @Override
    public boolean isLocked(String idempotentKey) {
        if (idempotentKey == null || idempotentKey.isBlank()) {
            return false;
        }
        String fullKey = buildFullKey(idempotentKey);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(fullKey));
        } catch (Exception e) {
            log.warn("[MailIdempotentGuard] Redis 查询幂等锁状态失败，key={}, 原因: {}", idempotentKey, e.getMessage(), e);
            return false;
        }
    }

    private String buildFullKey(String idempotentKey) {
        return properties.getRedisKeyPrefix() + idempotentKey;
    }
}
