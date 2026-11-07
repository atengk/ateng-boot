package io.github.atengk.boot.sms.properties;

import lombok.Data;

import java.io.Serializable;

/**
 * 短信出站安全防御与成本熔断配置属性
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
public class SmsGuardProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 单日全系统发信总配额熔断阈值（0 表示不设硬上限），默认 0
     */
    private long dailyMaxQuota = 0L;

    /**
     * 是否启用出站黑名单静默前置拦截，默认 true
     */
    private boolean blacklistEnabled = true;

    /**
     * 初始出站拦截手机号黑名单集合
     */
    private java.util.List<String> blacklist = new java.util.ArrayList<>();

    /**
     * 是否启用渠道健康度短时熔断保护，默认 true
     */
    private boolean circuitBreakerEnabled = true;

    /**
     * 渠道连续调用失败触发短时熔断的次数阈值，默认 5 次
     */
    private int circuitBreakerFailureThreshold = 5;

    /**
     * 渠道熔断后的冷却开路时长（到期后进入半开探测自愈态），默认 60 秒
     */
    private java.time.Duration circuitBreakerOpenDuration = java.time.Duration.ofSeconds(60);
}
