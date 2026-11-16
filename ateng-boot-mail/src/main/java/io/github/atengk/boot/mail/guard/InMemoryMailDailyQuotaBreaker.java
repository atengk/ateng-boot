package io.github.atengk.boot.mail.guard;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailQuotaProperties;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 ReentrantLock 与系统时钟的自然日总配额硬熔断单机默认实现
 * <p>
 * 无外部缓存依赖即可提供线程安全、无虚拟线程 Carrier Pinning 风险且跨日自动清零重置的总量配额熔断。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
public class InMemoryMailDailyQuotaBreaker implements MailDailyQuotaBreaker {

    private final MailQuotaProperties properties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private LocalDate activeDate;
    private long usedCount;

    public InMemoryMailDailyQuotaBreaker(MailQuotaProperties properties) {
        this(properties, Clock.systemDefaultZone());
    }

    public InMemoryMailDailyQuotaBreaker(MailQuotaProperties properties, Clock clock) {
        this.properties = properties != null ? properties : new MailQuotaProperties();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.activeDate = LocalDate.now(this.clock);
        this.usedCount = 0L;
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

        lock.lock();
        try {
            rollOverIfNeeded(today);
            if (usedCount + recipientCount > dailyLimit) {
                return new QuotaCheckResult(false, today, dailyLimit, usedCount, recipientCount);
            }
            usedCount += recipientCount;
            return new QuotaCheckResult(true, today, dailyLimit, usedCount, recipientCount);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public long getCurrentCount(LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        lock.lock();
        try {
            if (!targetDate.equals(activeDate)) {
                return 0L;
            }
            return usedCount;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void reset() {
        lock.lock();
        try {
            activeDate = LocalDate.now(clock);
            usedCount = 0L;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void rollOverIfNeeded(LocalDate today) {
        if (!today.equals(activeDate)) {
            activeDate = today;
            usedCount = 0L;
        }
    }
}
