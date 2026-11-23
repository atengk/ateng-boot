package io.github.atengk.boot.mail.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 邮件自然日总配额硬熔断配置属性
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Validated
public class MailQuotaProperties {

    /**
     * 是否启用自然日总配额硬熔断，默认 false（零侵入）
     */
    private boolean enabled = false;

    /**
     * 自然日允许发送的去重物理收件人次总量上限，默认 10000
     */
    @Min(value = 1, message = "quota.dailyMaxCount 必须大于或等于 1")
    private long dailyMaxCount = 10_000L;

    /**
     * Redis 日配额统计键前缀，默认 "ateng:mail:quota:"
     */
    @NotBlank(message = "quota.redisKeyPrefix 不能为空")
    private String redisKeyPrefix = "ateng:mail:quota:";

    /**
     * Redis 日配额统计键存活时间，默认 48 小时（跨日清理）
     */
    @NotNull(message = "quota.redisKeyTtl 不能为空")
    @ValidDuration(message = "quota.redisKeyTtl 必须大于 0")
    private Duration redisKeyTtl = Duration.ofHours(48);

    /**
     * 当 Redis 发生异常时是否高可用降级放行（默认 true）
     */
    private boolean failOpenOnRedisError = true;
}
