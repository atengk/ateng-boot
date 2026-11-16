package io.github.atengk.boot.mail.properties;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 邮件出站频率限制（账号令牌桶 + 单收件人滑动窗口）配置属性
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Validated
public class MailRateLimitProperties {

    /**
     * 是否启用邮件出站频率限制，默认 false（零侵入）
     */
    private boolean enabled = false;

    /**
     * 是否启用发信账号维度令牌桶平滑限流，默认 true
     */
    private boolean accountEnabled = true;

    /**
     * 发信账号令牌桶最大突发容量，默认 100
     */
    @Min(value = 1, message = "rateLimit.accountCapacity 必须大于或等于 1")
    private long accountCapacity = 100L;

    /**
     * 发信账号令牌桶每秒补充速率，默认 20.0
     */
    @DecimalMin(value = "0.0001", message = "rateLimit.accountPermitsPerSecond 必须大于 0")
    private double accountPermitsPerSecond = 20.0d;

    /**
     * 是否启用单收件人维度滑动窗口防轰炸，默认 true
     */
    private boolean recipientEnabled = true;

    /**
     * 单个收件人在统计窗口内允许的最大接收次数，默认 10
     */
    @Min(value = 1, message = "rateLimit.recipientMaxCount 必须大于或等于 1")
    private long recipientMaxCount = 10L;

    /**
     * 单收件人滑动窗口统计时长，默认 1 分钟
     */
    @NotNull(message = "rateLimit.recipientWindow 不能为空")
    @ValidDuration(message = "rateLimit.recipientWindow 必须大于 0")
    private Duration recipientWindow = Duration.ofMinutes(1);

    /**
     * 单机内存滑动窗口时间切片数量，默认 10，范围 1~120
     */
    @Min(value = 1, message = "rateLimit.recipientWindowSlices 必须在 1 到 120 之间")
    @Max(value = 120, message = "rateLimit.recipientWindowSlices 必须在 1 到 120 之间")
    private int recipientWindowSlices = 10;

    /**
     * 单机内存 LRU 限流器最大追踪键数量，默认 10000
     */
    @Min(value = 100, message = "rateLimit.maxTrackedKeys 必须大于或等于 100")
    private int maxTrackedKeys = 10_000;

    /**
     * Redis 分布式限流键前缀，默认 "ateng:mail:rate:"
     */
    @NotBlank(message = "rateLimit.redisKeyPrefix 不能为空")
    private String redisKeyPrefix = "ateng:mail:rate:";

    /**
     * 当 Redis 发生连接或脚本异常时是否高可用降级放行（默认 true，记录告警日志并放行；false 则严格阻断）
     */
    private boolean failOpenOnRedisError = true;
}
