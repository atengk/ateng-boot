package io.github.atengk.boot.mail.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 邮件出站幂等防重守卫配置属性
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Validated
public class MailIdempotentProperties {

    /**
     * 是否启用邮件出站幂等防重守卫，默认 false（零侵入）
     */
    private boolean enabled = false;

    /**
     * 是否仅在消息显式携带非空 bizId 时启用幂等校验，默认 true（避免无流水号常规通知内容一致被误拦）
     */
    private boolean requireBizId = true;

    /**
     * 幂等防重锁默认存活时长，默认 10 分钟
     */
    @NotNull(message = "idempotent.ttl 不能为空")
    @ValidDuration(message = "idempotent.ttl 必须大于 0")
    private Duration ttl = Duration.ofMinutes(10);

    /**
     * 单机内存 LRU 幂等缓存最大条目容量，默认 10000
     */
    @Min(value = 100, message = "idempotent.maxLocalCacheSize 必须大于或等于 100")
    private int maxLocalCacheSize = 10_000;

    /**
     * Redis 分布式幂等锁键前缀，默认 "ateng:mail:idempotent:"
     */
    @NotBlank(message = "idempotent.redisKeyPrefix 不能为空")
    private String redisKeyPrefix = "ateng:mail:idempotent:";

    /**
     * 当 Redis 发生异常时是否高可用降级放行（默认 true）
     */
    private boolean failOpenOnRedisError = true;
}
