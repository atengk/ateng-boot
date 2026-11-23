package io.github.atengk.boot.sms.metrics;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 短信出站执行终态度量标签枚举（强约束低基数 outcome 维度值）
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
@AllArgsConstructor
public enum SmsSendOutcome {

    /**
     * 发送成功
     */
    SUCCESS("success"),

    /**
     * 前置策略拦截（黑名单、免打扰或拦截器短路）
     */
    BLOCKED("blocked"),

    /**
     * 触发滑动窗口限流拦截
     */
    RATE_LIMITED("rate_limited"),

    /**
     * 触发单渠道配额上限拦截
     */
    QUOTA_EXCEEDED("quota_exceeded"),

    /**
     * 触发全局单日成本预算硬熔断拦截
     */
    BUDGET_EXCEEDED("budget_exceeded"),

    /**
     * 触发出站幂等窗口防重拦截
     */
    IDEMPOTENT_REJECTED("idempotent_rejected"),

    /**
     * 物理发送失败（含所有备用容灾降级渠道均失败）
     */
    FAILURE("failure");

    private final String tagValue;
}
