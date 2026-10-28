package io.github.atengk.boot.mail.properties;

import lombok.Data;

import java.time.Duration;

/**
 * 邮件出站物理发送弹性指数退避重试配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
public class MailRetryProperties {

    /**
     * 是否启用网络抖动指数退避重试，默认 false（由业务按需显式开启）
     */
    private boolean enabled = false;

    /**
     * 最大尝试总次数（包含首次执行，如 3 表示首次 + 2 次重试），默认 3
     */
    private int maxAttempts = 3;

    /**
     * 初始重试等待时长，默认 500 毫秒
     */
    private Duration delay = Duration.ofMillis(500);

    /**
     * 退避倍数因子，默认 2.0（指数退避）
     */
    private double multiplier = 2.0;

    /**
     * 单次重试等待时长上限，默认 5 秒
     */
    private Duration maxDelay = Duration.ofSeconds(5);

    /**
     * 是否启用 Equal Jitter 平滑随机抖动以打散重试洪峰，默认 true
     */
    private boolean jitter = true;
}
