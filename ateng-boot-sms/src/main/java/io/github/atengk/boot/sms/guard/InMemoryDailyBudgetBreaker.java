package io.github.atengk.boot.sms.guard;

import io.github.atengk.boot.sms.event.SmsBudgetExceededEvent;
import io.github.atengk.boot.sms.exception.SmsBudgetExceededException;
import io.github.atengk.boot.sms.properties.SmsGuardProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 纯内存单日成本预算熔断器默认实现
 * 基于原子 CAS 循环与跨日自动翻转，保障单机与单测环境 100% 离线自洽
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class InMemoryDailyBudgetBreaker implements DailyBudgetBreaker {

    private final SmsGuardProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicLong currentCount = new AtomicLong(0);
    private final AtomicReference<LocalDate> currentDate = new AtomicReference<>(LocalDate.now());

    public InMemoryDailyBudgetBreaker(SmsGuardProperties properties, ApplicationEventPublisher eventPublisher) {
        this.properties = properties != null ? properties : new SmsGuardProperties();
        this.eventPublisher = eventPublisher;
    }

    public InMemoryDailyBudgetBreaker(SmsGuardProperties properties) {
        this(properties, null);
    }

    public InMemoryDailyBudgetBreaker() {
        this(new SmsGuardProperties(), null);
    }

    @Override
    public void checkAndIncrement(int count) throws SmsBudgetExceededException {
        if (count <= 0) {
            return;
        }

        // 跨日自然翻转时自动归零
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate.get())) {
            currentDate.set(today);
            currentCount.set(0);
        }

        long quota = getDailyMaxQuota();
        if (quota <= 0) {
            // 0 表示不设硬上限
            currentCount.addAndGet(count);
            return;
        }

        // CAS 原子竞争累加防并发超限
        while (true) {
            long current = currentCount.get();
            if (current + count > quota) {
                log.error("[DailyBudgetBreaker] 短信单日累计发信量触发硬熔断 (当前累计: {}, 拟增: {}, 配额上限: {})",
                        current, count, quota);
                if (eventPublisher != null) {
                    eventPublisher.publishEvent(new SmsBudgetExceededEvent(this, quota, current, count));
                }
                throw new SmsBudgetExceededException(quota, current);
            }
            if (currentCount.compareAndSet(current, current + count)) {
                break;
            }
        }
    }

    @Override
    public long getDailyMaxQuota() {
        return properties.getDailyMaxQuota();
    }

    @Override
    public long getCurrentDailyCount() {
        return currentCount.get();
    }

    @Override
    public void reset() {
        currentCount.set(0);
        currentDate.set(LocalDate.now());
    }
}
