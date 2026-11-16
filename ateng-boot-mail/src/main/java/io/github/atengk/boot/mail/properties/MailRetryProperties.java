package io.github.atengk.boot.mail.properties;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 邮件出站物理发送弹性指数退避重试配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Validated
public class MailRetryProperties {

    /**
     * 是否启用网络抖动指数退避重试，默认 false（由业务按需显式开启）
     */
    private boolean enabled = false;

    /**
     * 最大尝试总次数（包含首次执行，如 3 表示首次 + 2 次重试），默认 3
     */
    @Min(value = 1, message = "retry.maxAttempts 必须大于或等于 1")
    private int maxAttempts = 3;

    /**
     * 初始重试等待时长，默认 500 毫秒
     */
    @NotNull(message = "retry.delay 不能为空")
    @ValidDuration(allowZero = true, message = "retry.delay 不能为负数")
    private Duration delay = Duration.ofMillis(500);

    /**
     * 退避倍数因子，默认 2.0（指数退避）
     */
    @DecimalMin(value = "1.0", message = "retry.multiplier 不能小于 1.0")
    private double multiplier = 2.0;

    /**
     * 单次重试等待时长上限，默认 5 秒
     */
    @NotNull(message = "retry.maxDelay 不能为空")
    @ValidDuration(allowZero = true, message = "retry.maxDelay 不能为负数")
    private Duration maxDelay = Duration.ofSeconds(5);

    /**
     * 是否启用 Equal Jitter 平滑随机抖动以打散重试洪峰，默认 true
     */
    private boolean jitter = true;
}
