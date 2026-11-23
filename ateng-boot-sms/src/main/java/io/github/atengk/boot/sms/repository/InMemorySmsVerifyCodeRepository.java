package io.github.atengk.boot.sms.repository;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.exception.SmsVerifyCodeException;
import io.github.atengk.boot.sms.support.BoundedLruMap;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;

/**
 * 纯内存本地验证码仓储实现
 * 100% 离线自洽，依托 BoundedLruMap 与 ReentrantLock 实现同锁原子冷却校验写入、防重放销毁与防暴力破解冻结
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class InMemorySmsVerifyCodeRepository implements SmsVerifyCodeRepository {

    private final BoundedLruMap<String, CodeEntry> storage;
    private final Clock clock;

    public InMemorySmsVerifyCodeRepository(int maxEntries, Clock clock) {
        this.storage = new BoundedLruMap<>(maxEntries);
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    public InMemorySmsVerifyCodeRepository(int maxEntries) {
        this(maxEntries, Clock.systemDefaultZone());
    }

    public InMemorySmsVerifyCodeRepository(Clock clock) {
        this(BoundedLruMap.DEFAULT_MAX_ENTRIES, clock);
    }

    public InMemorySmsVerifyCodeRepository() {
        this(BoundedLruMap.DEFAULT_MAX_ENTRIES, Clock.systemDefaultZone());
    }

    @Override
    public void checkCooldown(String phone, String bizType) {
        if (!org.springframework.util.StringUtils.hasText(phone)) {
            return;
        }
        String key = buildKey(phone, bizType);
        long now = clock.millis();
        storage.withLock(view -> {
            checkCooldownLocked(view, key, now);
            return null;
        });
    }

    @Override
    public void save(String phone, String bizType, String code, Duration ttl, Duration resendInterval, int maxRetryAttempts) {
        BusinessAssert.hasText(phone, "目标手机号不能为空");
        BusinessAssert.hasText(code, "验证码内容不能为空");

        String key = buildKey(phone, bizType);
        long now = clock.millis();
        long expireAt = now + (ttl != null ? ttl.toMillis() : Duration.ofMinutes(5).toMillis());
        long resendAfter = now + (resendInterval != null ? resendInterval.toMillis() : Duration.ofSeconds(60).toMillis());
        CodeEntry newEntry = new CodeEntry(code, expireAt, resendAfter, maxRetryAttempts > 0 ? maxRetryAttempts : 5, false, false);

        // 冷却检查、容量淘汰与新码写入收敛至同一互斥锁原子执行，杜绝并发竞态穿透冷却
        storage.withLock(view -> {
            checkCooldownLocked(view, key, now);
            view.put(key, newEntry, entry -> entry == null || now > entry.getExpireAt());
            return null;
        });
    }

    @Override
    public boolean verify(String phone, String code, String bizType) {
        BusinessAssert.hasText(phone, "目标手机号不能为空");
        BusinessAssert.hasText(code, "待核验验证码不能为空");

        String key = buildKey(phone, bizType);
        long now = clock.millis();
        String maskedPhone = DesensitizeUtil.mobilePhone(phone);

        return storage.withLock(view -> {
            CodeEntry entry = view.get(key);
            if (entry == null || now > entry.getExpireAt() || entry.isConsumed()) {
                view.remove(key);
                throw new SmsVerifyCodeException("验证码不存在或已过期", 0, false);
            }

            if (entry.isFrozen()) {
                throw new SmsVerifyCodeException("验证码连续错误过多，已被锁定", 0, true);
            }

            if (entry.getCode().equalsIgnoreCase(code.trim())) {
                entry.setConsumed(true);
                view.remove(key);
                log.info("[InMemorySmsVerifyCodeRepository] 验证码核验成功并立即物理销毁 (phone: {}, bizType: {})", maskedPhone, bizType);
                return true;
            }

            entry.setRemainingAttempts(entry.getRemainingAttempts() - 1);
            if (entry.getRemainingAttempts() <= 0) {
                entry.setFrozen(true);
                log.warn("[InMemorySmsVerifyCodeRepository] 验证码连续错误达到上限，强制冻结作废 (phone: {}, bizType: {})", maskedPhone, bizType);
                throw new SmsVerifyCodeException("验证码连续错误过多，已被锁定", 0, true);
            }

            log.warn("[InMemorySmsVerifyCodeRepository] 验证码核验错误，剩余可重试次数: {}", entry.getRemainingAttempts());
            throw new SmsVerifyCodeException("验证码错误，剩余重试次数: " + entry.getRemainingAttempts(),
                    entry.getRemainingAttempts(), false);
        });
    }

    @Override
    public boolean check(String phone, String code, String bizType) {
        if (!org.springframework.util.StringUtils.hasText(phone) || !org.springframework.util.StringUtils.hasText(code)) {
            return false;
        }
        String key = buildKey(phone, bizType);
        long now = clock.millis();

        return storage.withLock(view -> {
            CodeEntry entry = view.get(key);
            if (entry == null || now > entry.getExpireAt() || entry.isFrozen() || entry.isConsumed()) {
                return false;
            }
            return entry.getCode().equalsIgnoreCase(code.trim());
        });
    }

    @Override
    public void remove(String phone, String bizType) {
        if (phone != null) {
            storage.remove(buildKey(phone, bizType));
        }
    }

    /**
     * 获取当前在存验证码条目数（用于容量监控与淘汰验证）
     *
     * @return 条目数
     */
    public int size() {
        return storage.size();
    }

    private void checkCooldownLocked(BoundedLruMap.StorageView<String, CodeEntry> view, String key, long now) {
        CodeEntry existing = view.get(key);
        if (existing == null) {
            return;
        }
        if (now > existing.getExpireAt()) {
            view.remove(key);
            return;
        }
        if (now < existing.getResendAfter()) {
            long waitMillis = existing.getResendAfter() - now;
            long waitSeconds = Math.max(1, waitMillis / 1000);
            log.warn("[InMemorySmsVerifyCodeRepository] 验证码处于重发冷却期内，拦截重发请求 (还需等待: {} 秒)", waitSeconds);
            throw new SmsRateLimitException("验证码发送过于频繁，请等待 " + waitSeconds + " 秒后再试", waitSeconds);
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
        private boolean consumed;
    }
}
