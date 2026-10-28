package io.github.atengk.boot.notification.limiter;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.properties.NotificationRateLimitProperties;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 协同通知单机内存滑动窗口频控器
 * 基于 JDK 17 原生并发原语（ReentrantLock 与 Condition）实现精确滑动窗口频控与微退避排队治理
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class InMemoryNotificationRateLimiter implements NotificationRateLimiter {

    private final NotificationRateLimitProperties properties;
    private final Duration windowDuration;
    private final ConcurrentHashMap<String, ChannelWindowLimiter> limiters = new ConcurrentHashMap<>();

    public InMemoryNotificationRateLimiter(NotificationRateLimitProperties properties) {
        this(properties, Duration.ofMinutes(1));
    }

    public InMemoryNotificationRateLimiter(NotificationRateLimitProperties properties, Duration windowDuration) {
        this.properties = properties != null ? properties : new NotificationRateLimitProperties();
        this.windowDuration = windowDuration != null ? windowDuration : Duration.ofMinutes(1);
    }

    @Override
    public boolean tryAcquire(NotificationChannel channel, Duration maxWaitDuration) {
        BusinessAssert.notNull(channel, "通知渠道不能为空");
        BusinessAssert.isTrue(channel.getName() != null && !channel.getName().isBlank(), "渠道名称不能为空");

        if (!properties.isEnabled()) {
            return true;
        }

        int permits = resolvePermitsPerMinute(channel);
        ChannelWindowLimiter limiter = limiters.computeIfAbsent(channel.getName(),
                name -> new ChannelWindowLimiter(windowDuration.toNanos()));

        return limiter.tryAcquire(permits, maxWaitDuration);
    }

    @Override
    public int resolvePermitsPerMinute(NotificationChannel channel) {
        if (channel == null) {
            return properties.getDefaultGenericPermits();
        }
        if (channel.getPermitsPerMinute() != null && channel.getPermitsPerMinute() > 0) {
            return channel.getPermitsPerMinute();
        }
        Platform platform = channel.getPlatform();
        if (platform == null) {
            return properties.getDefaultGenericPermits();
        }
        return switch (platform) {
            case DINGTALK -> properties.getDefaultDingTalkPermits();
            case WECOM -> properties.getDefaultWeComPermits();
            case FEISHU -> properties.getDefaultFeishuPermits();
            case GENERIC -> properties.getDefaultGenericPermits();
        };
    }

    @Override
    public Duration getDefaultMaxWaitDuration() {
        return properties.getMaxWaitDuration() != null ? properties.getMaxWaitDuration() : Duration.ofSeconds(2);
    }

    @Override
    public void reset(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return;
        }
        ChannelWindowLimiter limiter = limiters.get(channelName);
        if (limiter != null) {
            limiter.reset();
        }
    }

    @Override
    public void resetAll() {
        limiters.values().forEach(ChannelWindowLimiter::reset);
    }

    /**
     * 获取指定渠道当前滑动窗口内剩余可用配额
     *
     * @param channel 目标通知渠道
     * @return 剩余可用配额数
     */
    public int getAvailablePermits(NotificationChannel channel) {
        if (channel == null || !properties.isEnabled()) {
            return Integer.MAX_VALUE;
        }
        int permits = resolvePermitsPerMinute(channel);
        ChannelWindowLimiter limiter = limiters.get(channel.getName());
        if (limiter == null) {
            return permits;
        }
        return limiter.getAvailablePermits(permits);
    }

    /**
     * 获取当前处于受管状态的独立渠道数
     *
     * @return 渠道数
     */
    public int getActiveChannelsCount() {
        return limiters.size();
    }

    /**
     * 单渠道内部滑动窗口与排队治理实体
     */
    private static class ChannelWindowLimiter {

        private final long windowNanos;
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition condition = lock.newCondition();
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private int waitingCount = 0;

        ChannelWindowLimiter(long windowNanos) {
            this.windowNanos = windowNanos;
        }

        boolean tryAcquire(int permits, Duration maxWaitDuration) {
            if (permits <= 0) {
                return false;
            }
            long maxWaitNanos = (maxWaitDuration != null && !maxWaitDuration.isNegative())
                    ? maxWaitDuration.toNanos()
                    : 0L;

            long startNanos = System.nanoTime();

            if (maxWaitNanos <= 0L) {
                if (!lock.tryLock()) {
                    return false;
                }
                try {
                    long now = System.nanoTime();
                    cleanExpired(now);
                    if (timestamps.size() < permits) {
                        timestamps.addLast(now);
                        return true;
                    }
                    return false;
                } finally {
                    lock.unlock();
                }
            }

            try {
                if (!lock.tryLock(maxWaitNanos, TimeUnit.NANOSECONDS)) {
                    return false;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }

            try {
                while (true) {
                    long now = System.nanoTime();
                    cleanExpired(now);

                    // 场景一：当前窗口尚有可用配额，直接占用并发放许可
                    if (timestamps.size() < permits) {
                        timestamps.addLast(now);
                        return true;
                    }

                    long elapsedNanos = System.nanoTime() - startNanos;
                    long remainingNanos = maxWaitNanos - elapsedNanos;

                    if (remainingNanos <= 0L) {
                        return false;
                    }

                    // 场景二：配额已满，等待队首（最早占用的元素）过期释放
                    Long oldestTimestamp = timestamps.peekFirst();
                    if (oldestTimestamp == null) {
                        continue;
                    }
                    long waitNanos = windowNanos - (now - oldestTimestamp);

                    if (waitNanos <= 0L) {
                        continue;
                    }

                    if (waitNanos > remainingNanos) {
                        return false;
                    }

                    waitingCount++;
                    try {
                        condition.await(waitNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return false;
                    } finally {
                        waitingCount--;
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        int getAvailablePermits(int permits) {
            lock.lock();
            try {
                long now = System.nanoTime();
                cleanExpired(now);
                return Math.max(0, permits - timestamps.size());
            } finally {
                lock.unlock();
            }
        }

        void reset() {
            lock.lock();
            try {
                timestamps.clear();
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void cleanExpired(long now) {
            while (!timestamps.isEmpty() && (now - timestamps.peekFirst()) >= windowNanos) {
                timestamps.pollFirst();
            }
        }
    }
}
