package io.github.atengk.boot.notification.properties;

import lombok.Data;

import java.time.Duration;

/**
 * 协同通知网络重试退避配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class NotificationRetryProperties {

    /**
     * 是否开启失败退避重试，默认开启
     */
    private boolean enabled = true;

    /**
     * 最大重试次数（包含首次调用），默认 3 次
     */
    private int maxAttempts = 3;

    /**
     * 初始退避间隔，默认 500 毫秒
     */
    private Duration initialInterval = Duration.ofMillis(500);

    /**
     * 指数退避乘数，默认 2.0
     */
    private double multiplier = 2.0;

    /**
     * 最大退避间隔，默认 3 秒
     */
    private Duration maxInterval = Duration.ofSeconds(3);
}
