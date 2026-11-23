package io.github.atengk.boot.sms.exception;

import lombok.Getter;

/**
 * 短信单日成本预算熔断异常
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
public class SmsBudgetExceededException extends SmsException {

    private static final long serialVersionUID = 1L;

    /**
     * 单日配额阈值
     */
    private final long dailyQuota;

    /**
     * 当前已累计发送量
     */
    private final long currentSent;

    /**
     * 是否由单渠道周期配额触限引发（true=单渠道配额耗尽，false=全局单日总预算触限）
     */
    private final boolean channelQuota;

    public SmsBudgetExceededException(long dailyQuota, long currentSent) {
        this(dailyQuota, currentSent, false);
    }

    public SmsBudgetExceededException(long quota, long currentSent, boolean channelQuota) {
        super(SmsResultCode.SMS_BUDGET_EXCEEDED,
                channelQuota
                        ? String.format("短信渠道发送量达到配额上限 (当前累计: %d, 配额上限: %d)", currentSent, quota)
                        : String.format("短信单日发送量达到上限 (当前累计: %d, 配额上限: %d)", currentSent, quota));
        this.dailyQuota = quota;
        this.currentSent = currentSent;
        this.channelQuota = channelQuota;
    }
}
