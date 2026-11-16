package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.core.concurrent.SlidingWindowCounter;
import io.github.atengk.boot.core.concurrent.TokenBucketLimiter;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailRateLimitProperties;

import java.util.Collection;

/**
 * 基于 ateng-boot-core 内存并发原语的邮件出站频率限制默认实现
 * <p>
 * 零外部中间件依赖，复用 {@link ConcurrentLruCache}、{@link TokenBucketLimiter}（发信账号平滑限流）与
 * {@link SlidingWindowCounter}（单收件人滑动窗口防轰炸）构建有界线程安全进程内频控。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
public class InMemoryMailRateLimiter implements MailRateLimiter {

    private final MailRateLimitProperties properties;
    private final ConcurrentLruCache<String, TokenBucketLimiter> accountLimiters;
    private final ConcurrentLruCache<String, SlidingWindowCounter> recipientWindows;

    public InMemoryMailRateLimiter(MailRateLimitProperties properties) {
        this.properties = properties != null ? properties : new MailRateLimitProperties();
        int maxKeys = Math.max(100, this.properties.getMaxTrackedKeys());
        this.accountLimiters = new ConcurrentLruCache<>(maxKeys);
        this.recipientWindows = new ConcurrentLruCache<>(maxKeys);
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

        // 1. 若开启单收件人防轰炸，先预检所有收件人滑动窗口余量，尽量减少超限请求对其他收件人及账号桶的无效损耗
        if (properties.isRecipientEnabled()) {
            long recipientMax = properties.getRecipientMaxCount();
            for (String recipient : deduplicatedRecipients) {
                if (recipient == null || recipient.isBlank()) {
                    continue;
                }
                SlidingWindowCounter counter = getOrCreateRecipientCounter(recipient);
                if (counter.getCount() + 1 > recipientMax) {
                    return false;
                }
            }
        }

        // 2. 发信账号维度令牌桶平滑限流（按去重物理收件人次扣减令牌）
        if (properties.isAccountEnabled()) {
            String accountKey = MailGuardSupport.resolveAccountKey(account);
            TokenBucketLimiter limiter = getOrCreateAccountLimiter(accountKey);
            if (!limiter.tryAcquire(recipientCount)) {
                return false;
            }
        }

        // 3. 单收件人维度滑动窗口原子准入与递增
        if (properties.isRecipientEnabled()) {
            long recipientMax = properties.getRecipientMaxCount();
            for (String recipient : deduplicatedRecipients) {
                if (recipient == null || recipient.isBlank()) {
                    continue;
                }
                SlidingWindowCounter counter = getOrCreateRecipientCounter(recipient);
                if (!counter.tryAcquire(1, recipientMax)) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public void reset() {
        accountLimiters.clear();
        recipientWindows.clear();
    }

    private TokenBucketLimiter getOrCreateAccountLimiter(String accountKey) {
        return accountLimiters.computeIfAbsent(accountKey,
                k -> new TokenBucketLimiter(properties.getAccountCapacity(), properties.getAccountPermitsPerSecond()));
    }

    private SlidingWindowCounter getOrCreateRecipientCounter(String recipient) {
        String normalized = MailGuardSupport.normalizeRecipient(recipient);
        return recipientWindows.computeIfAbsent(normalized,
                k -> new SlidingWindowCounter(properties.getRecipientWindow(), properties.getRecipientWindowSlices()));
    }
}
