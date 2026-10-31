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
        int burstPermits = resolveBurstPermitsPerSecond(channel);
        ChannelWindowLimiter limiter = limiters.computeIfAbsent(channel.getName(),
                name -> new ChannelWindowLimiter(windowDuration.toNanos()));

        return limiter.tryAcquire(permits, burstPermits, maxWaitDuration);
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
    public int resolveBurstPermitsPerSecond(NotificationChannel channel) {
        if (channel == null || !properties.isEnabled()) {
            return 0;
        }
        if (channel.getBurstPermitsPerSecond() != null && channel.getBurstPermitsPerSecond() > 0) {
            return channel.getBurstPermitsPerSecond();
        }
        Platform platform = channel.getPlatform();
        if (platform == Platform.FEISHU) {
            return properties.getDefaultFeishuBurstPermits();
        }
        return 0;
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
        int burstPermits = resolveBurstPermitsPerSecond(channel);
        ChannelWindowLimiter limiter = limiters.get(channel.getName());
        if (limiter == null) {
            return burstPermits > 0 ? Math.min(permits, burstPermits) : permits;
        }
        return limiter.getAvailablePermits(permits, burstPermits);
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
        private final long burstWindowNanos = TimeUnit.SECONDS.toNanos(1);
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition condition = lock.newCondition();
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private final Deque<Long> secondTimestamps = new ArrayDeque<>();
        private int waitingCount = 0;

        ChannelWindowLimiter(long windowNanos) {
            this.windowNanos = windowNanos;
        }

        boolean tryAcquire(int permits, Duration maxWaitDuration) {
            return tryAcquire(permits, 0, maxWaitDuration);
        }

        boolean tryAcquire(int permits, int burstPermits, Duration maxWaitDuration) {
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
                    cleanExpired(now, burstPermits);
                    boolean minuteAvailable = timestamps.size() < permits;
                    boolean burstAvailable = burstPermits <= 0 || secondTimestamps.size() < burstPermits;
                    if (minuteAvailable && burstAvailable) {
                        timestamps.addLast(now);
                        if (burstPermits > 0) {
                            secondTimestamps.addLast(now);
                        }
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
                    cleanExpired(now, burstPermits);

                    boolean minuteAvailable = timestamps.size() < permits;
                    boolean burstAvailable = burstPermits <= 0 || secondTimestamps.size() < burstPermits;

                    // 场景一：双阶窗口均有可用配额，直接占用并发放许可
                    if (minuteAvailable && burstAvailable) {
                        timestamps.addLast(now);
                        if (burstPermits > 0) {
                            secondTimestamps.addLast(now);
                        }
                        return true;
                    }

                    long elapsedNanos = System.nanoTime() - startNanos;
                    long remainingNanos = maxWaitNanos - elapsedNanos;

                    if (remainingNanos <= 0L) {
                        return false;
                    }

                    // 场景二：配额已满，计算需要等待的时长
                    long waitNanos = 0L;
                    if (!minuteAvailable) {
                        Long oldestTimestamp = timestamps.peekFirst();
                        if (oldestTimestamp != null) {
                            waitNanos = Math.max(waitNanos, windowNanos - (now - oldestTimestamp));
                        }
                    }
                    if (!burstAvailable) {
                        Long oldestSecond = secondTimestamps.peekFirst();
                        if (oldestSecond != null) {
                            waitNanos = Math.max(waitNanos, burstWindowNanos - (now - oldestSecond));
                        }
                    }

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
            return getAvailablePermits(permits, 0);
        }

        int getAvailablePermits(int permits, int burstPermits) {
            lock.lock();
            try {
                long now = System.nanoTime();
                cleanExpired(now, burstPermits);
                int minuteAvail = Math.max(0, permits - timestamps.size());
                if (burstPermits > 0) {
                    int burstAvail = Math.max(0, burstPermits - secondTimestamps.size());
                    return Math.min(minuteAvail, burstAvail);
                }
                return minuteAvail;
            } finally {
                lock.unlock();
            }
        }

        void reset() {
            lock.lock();
            try {
                timestamps.clear();
                secondTimestamps.clear();
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void cleanExpired(long now, int burstPermits) {
            while (!timestamps.isEmpty() && (now - timestamps.peekFirst()) >= windowNanos) {
                timestamps.pollFirst();
            }
            if (burstPermits > 0) {
                while (!secondTimestamps.isEmpty() && (now - secondTimestamps.peekFirst()) >= burstWindowNanos) {
                    secondTimestamps.pollFirst();
                }
            }
        }
    }
}
