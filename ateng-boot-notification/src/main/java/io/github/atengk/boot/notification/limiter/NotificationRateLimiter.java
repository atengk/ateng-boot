package io.github.atengk.boot.notification.limiter;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationRateLimitException;

import java.time.Duration;

/**
 * 协同通知客户端通道频控器契约
 * 负责单通道发信速率防卫并提供微退避排队能力（tryAcquire）
 *
 * @author Ateng
 * @since 2026-10-23
 */
public interface NotificationRateLimiter {

    /**
     * 尝试获取指定通道的发信许可（带最大微退避排队超时）
     *
     * @param channel         目标通知渠道
     * @param maxWaitDuration 最大排队等待时长（为 null 或 <= 0 时表示立即返回不等待）
     * @return true 若在超时前成功获取到发信配额；false 若配额耗尽且等待超时
     */
    boolean tryAcquire(NotificationChannel channel, Duration maxWaitDuration);

    /**
     * 尝试立即获取指定通道的发信许可（非阻塞，超时时间为 0）
     *
     * @param channel 目标通知渠道
     * @return true 若当前有空闲配额；false 若配额已耗尽
     */
    default boolean tryAcquire(NotificationChannel channel) {
        return tryAcquire(channel, Duration.ZERO);
    }

    /**
     * 阻塞并排队获取指定通道的发信许可，若等待超时则直接抛出受控业务异常 {@link NotificationRateLimitException}
     *
     * @param channel         目标通知渠道
     * @param maxWaitDuration 最大排队等待时长
     * @throws NotificationRateLimitException 当等待超时仍未获取到配额时抛出
     */
    default void acquire(NotificationChannel channel, Duration maxWaitDuration) throws NotificationRateLimitException {
        Duration effectiveWait = maxWaitDuration != null ? maxWaitDuration : getDefaultMaxWaitDuration();
        if (!tryAcquire(channel, effectiveWait)) {
            int permits = resolvePermitsPerMinute(channel);
            String channelName = channel != null ? channel.getName() : "unknown";
            throw new NotificationRateLimitException(
                    channelName,
                    permits,
                    effectiveWait,
                    String.format("渠道 [%s] 触发客户端发信频控超限 (配额: %d 条/分钟)，微退避排队等待 %d ms 后依然耗尽",
                            channelName,
                            permits,
                            effectiveWait.toMillis())
            );
        }
    }

    /**
     * 阻塞并排队获取指定通道的发信许可（采用频控默认最大等待时长），若等待超时则直接抛出受控业务异常 {@link NotificationRateLimitException}
     *
     * @param channel 目标通知渠道
     * @throws NotificationRateLimitException 当等待超时仍未获取到配额时抛出
     */
    default void acquire(NotificationChannel channel) throws NotificationRateLimitException {
        acquire(channel, getDefaultMaxWaitDuration());
    }

    /**
     * 解析指定通道生效的每分钟配额阈值（条/分钟）
     * 优先使用渠道自定义配额，缺省按平台基准配额兜底
     *
     * @param channel 目标通知渠道
     * @return 每分钟允许的最大发信条数
     */
    int resolvePermitsPerMinute(NotificationChannel channel);

    /**
     * 获取频控器配置的默认最大微退避排队等待时长
     *
     * @return 默认最大排队等待时长
     */
    default Duration getDefaultMaxWaitDuration() {
        return Duration.ofSeconds(2);
    }

    /**
     * 清理或重置指定通道的频控状态
     *
     * @param channelName 渠道标识名称
     */
    default void reset(String channelName) {
        // 缺省空实现
    }

    /**
     * 清理或重置所有通道的频控状态
     */
    default void resetAll() {
        // 缺省空实现
    }
}
