package io.github.atengk.boot.sms.limiter;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.properties.SmsRateLimitProperties;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯内存本地滑动窗口短信频控限流器
 * 当应用未接入 Redis 或处于单机离线环境时自动启用
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
public class InMemorySmsRateLimiter implements SmsRateLimiter {

    private final SmsRateLimitProperties properties;
    private final Map<String, Deque<Long>> phoneTimestamps = new ConcurrentHashMap<>();

    public InMemorySmsRateLimiter(SmsRateLimitProperties properties) {
        this.properties = properties != null ? properties : new SmsRateLimitProperties();
    }

    public InMemorySmsRateLimiter() {
        this(new SmsRateLimitProperties());
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

        Deque<Long> queue = phoneTimestamps.computeIfAbsent(phone, k -> new ArrayDeque<>());
        synchronized (queue) {
            // 1. 淘汰已超过 24 小时的过期历史时间戳
            while (!queue.isEmpty() && queue.peekFirst() < oneDayAgo) {
                queue.pollFirst();
            }

            // 2. 统计各滑动窗口阶梯的发送条数
            int minuteCount = 0;
            int hourCount = 0;
            int dayCount = queue.size();

            Long oldestMinuteTs = null;
            Long oldestHourTs = null;

            for (Long ts : queue) {
                if (ts >= oneMinuteAgo) {
                    minuteCount++;
                    if (oldestMinuteTs == null) {
                        oldestMinuteTs = ts;
                    }
                }
                if (ts >= oneHourAgo) {
                    hourCount++;
                    if (oldestHourTs == null) {
                        oldestHourTs = ts;
                    }
                }
            }

            // 3. 校验 1 分钟阶梯
            if (properties.getPerMinute() > 0 && minuteCount >= properties.getPerMinute()) {
                long waitMillis = oldestMinuteTs != null ? (oldestMinuteTs + 60_000L - now) : 60_000L;
                long waitSeconds = Math.max(1, waitMillis / 1000);
                log.warn("[InMemorySmsRateLimiter] 手机号触达 1 分钟发信上限 (当前: {}, 上限: {})", minuteCount, properties.getPerMinute());
                throw new SmsRateLimitException("短信发送过于频繁，请等待 " + waitSeconds + " 秒后再试", waitSeconds);
            }

            // 4. 校验 1 小时阶梯
            if (properties.getPerHour() > 0 && hourCount >= properties.getPerHour()) {
                long waitMillis = oldestHourTs != null ? (oldestHourTs + 3_600_000L - now) : 3_600_000L;
                long waitSeconds = Math.max(1, waitMillis / 1000);
                log.warn("[InMemorySmsRateLimiter] 手机号触达 1 小时发信上限 (当前: {}, 上限: {})", hourCount, properties.getPerHour());
                throw new SmsRateLimitException("短信发送过于频繁，1小时内发送次数已达上限", waitSeconds);
            }

            // 5. 校验 1 天阶梯
            if (properties.getPerDay() > 0 && dayCount >= properties.getPerDay()) {
                log.warn("[InMemorySmsRateLimiter] 手机号触达单日发信上限 (当前: {}, 上限: {})", dayCount, properties.getPerDay());
                throw new SmsRateLimitException("今日短信发送次数已达上限，请明天再试", 86400L);
            }

            // 6. 成功记录当前时间戳
            queue.addLast(now);
        }
    }

    @Override
    public void reset(String phone) {
        if (phone != null) {
            phoneTimestamps.remove(phone);
        }
    }
}
