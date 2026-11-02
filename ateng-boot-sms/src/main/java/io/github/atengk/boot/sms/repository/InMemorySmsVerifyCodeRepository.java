package io.github.atengk.boot.sms.repository;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.exception.SmsVerifyCodeException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯内存本地验证码仓储实现
 * 100% 离线自洽，具备原子防重放销毁与防暴力破解连续输错冻结机制
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class InMemorySmsVerifyCodeRepository implements SmsVerifyCodeRepository {

    private final Map<String, CodeEntry> storage = new ConcurrentHashMap<>();

    @Override
    public void save(String phone, String bizType, String code, Duration ttl, Duration resendInterval, int maxRetryAttempts) {
        BusinessAssert.hasText(phone, "目标手机号不能为空");
        BusinessAssert.hasText(code, "验证码内容不能为空");

        String key = buildKey(phone, bizType);
        long now = Instant.now().toEpochMilli();

        // 1. 检查是否存在未过期的验证码并校验重发冷却期
        CodeEntry existing = storage.get(key);
        if (existing != null && now < existing.getExpireAt()) {
            if (now < existing.getResendAfter()) {
                long waitMillis = existing.getResendAfter() - now;
                long waitSeconds = Math.max(1, waitMillis / 1000);
                log.warn("[InMemorySmsVerifyCodeRepository] 验证码处于重发冷却期内，拦截重发请求 (还需等待: {} 秒)", waitSeconds);
                throw new SmsRateLimitException("验证码发送过于频繁，请等待 " + waitSeconds + " 秒后再试", waitSeconds);
            }
        }

        // 2. 组装并写入新验证码元数据
        long expireAt = now + (ttl != null ? ttl.toMillis() : Duration.ofMinutes(5).toMillis());
        long resendAfter = now + (resendInterval != null ? resendInterval.toMillis() : Duration.ofSeconds(60).toMillis());

        CodeEntry newEntry = new CodeEntry(code, expireAt, resendAfter, maxRetryAttempts > 0 ? maxRetryAttempts : 5, false);
        storage.put(key, newEntry);
    }

    @Override
    public boolean verify(String phone, String code, String bizType) {
        BusinessAssert.hasText(phone, "目标手机号不能为空");
        BusinessAssert.hasText(code, "待核验验证码不能为空");

        String key = buildKey(phone, bizType);
        long now = Instant.now().toEpochMilli();

        CodeEntry entry = storage.get(key);
        if (entry == null || now > entry.getExpireAt()) {
            storage.remove(key);
            throw new SmsVerifyCodeException("验证码不存在或已过期", 0, false);
        }

        synchronized (entry) {
            // 1. 检查是否已被冻结
            if (entry.isFrozen()) {
                throw new SmsVerifyCodeException("验证码连续错误过多，已被锁定", 0, true);
            }

            // 2. 验证码匹配成功：立即物理删除，杜绝重放攻击
            if (entry.getCode().equalsIgnoreCase(code.trim())) {
                storage.remove(key);
                log.info("[InMemorySmsVerifyCodeRepository] 验证码核验成功并立即物理销毁 (phone: {}, bizType: {})", phone, bizType);
                return true;
            }

            // 3. 验证码错误：扣减重试次数
            entry.setRemainingAttempts(entry.getRemainingAttempts() - 1);
            if (entry.getRemainingAttempts() <= 0) {
                entry.setFrozen(true);
                log.warn("[InMemorySmsVerifyCodeRepository] 验证码连续错误达到上限，强制冻结作废 (phone: {}, bizType: {})", phone, bizType);
                throw new SmsVerifyCodeException("验证码连续错误过多，已被锁定", 0, true);
            }

            log.warn("[InMemorySmsVerifyCodeRepository] 验证码核验错误，剩余可重试次数: {}", entry.getRemainingAttempts());
            throw new SmsVerifyCodeException("验证码错误，剩余重试次数: " + entry.getRemainingAttempts(),
                    entry.getRemainingAttempts(), false);
        }
    }

    @Override
    public boolean check(String phone, String code, String bizType) {
        if (!org.springframework.util.StringUtils.hasText(phone) || !org.springframework.util.StringUtils.hasText(code)) {
            return false;
        }
        String key = buildKey(phone, bizType);
        long now = Instant.now().toEpochMilli();

        CodeEntry entry = storage.get(key);
        if (entry == null || now > entry.getExpireAt() || entry.isFrozen()) {
            return false;
        }
        return entry.getCode().equalsIgnoreCase(code.trim());
    }

    @Override
    public void remove(String phone, String bizType) {
        if (phone != null) {
            storage.remove(buildKey(phone, bizType));
        }
    }

    private String buildKey(String phone, String bizType) {
        return phone + ":" + (bizType != null ? bizType.trim() : "default");
    }

    @Data
    @AllArgsConstructor
    private static class CodeEntry {
        private String code;
        private long expireAt;
        private long resendAfter;
        private int remainingAttempts;
        private boolean frozen;
    }
}
