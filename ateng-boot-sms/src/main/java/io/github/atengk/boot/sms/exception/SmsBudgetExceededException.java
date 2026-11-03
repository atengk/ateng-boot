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

    public SmsBudgetExceededException(long dailyQuota, long currentSent) {
        super(SmsResultCode.SMS_BUDGET_EXCEEDED, String.format("短信单日发送量达到上限 (当前累计: %d, 配额上限: %d)", currentSent, dailyQuota));
        this.dailyQuota = dailyQuota;
        this.currentSent = currentSent;
    }
}
