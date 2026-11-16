package io.github.atengk.boot.mail.metrics;

import lombok.Getter;

/**
 * 邮件发信操作终态结果枚举（严格低基数 Micrometer {@code outcome} 标签值契约）
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Getter
public enum MailSendOutcome {

    /**
     * 物理 SMTP 投递成功（含主备容灾成功）
     */
    SUCCESS("success"),

    /**
     * 测试沙箱纯日志模拟放行（未触发物理发信）
     */
    SANDBOX("sandbox"),

    /**
     * 被 {@code MailSendInterceptor} 拦截阻断
     */
    INTERCEPTED("intercepted"),

    /**
     * 被 {@code MailRateLimiter} 出站频率限制拦截
     */
    RATE_LIMITED("rate_limited"),

    /**
     * 被 {@code MailDailyQuotaBreaker} 自然日总配额硬熔断拦截
     */
    QUOTA_EXCEEDED("quota_exceeded"),

    /**
     * 被 {@code MailIdempotentGuard} 出站幂等防重拦截
     */
    IDEMPOTENT_REJECTED("idempotent_rejected"),

    /**
     * 其他发送失败（SMTP 网络/认证异常、地址校验失败、模板异常或批量超时等）
     */
    FAILURE("failure");

    private final String tagValue;

    MailSendOutcome(String tagValue) {
        this.tagValue = tagValue;
    }
}
