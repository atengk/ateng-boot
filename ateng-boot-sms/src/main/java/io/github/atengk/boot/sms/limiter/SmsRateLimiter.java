package io.github.atengk.boot.sms.limiter;

import io.github.atengk.boot.sms.exception.SmsRateLimitException;

/**
 * 短信多阶滑动窗口频控防刷限流器
 *
 * @author Ateng
 * @since 2026-11-02
 */
public interface SmsRateLimiter {

    /**
     * 尝试获取发信配额许可
     *
     * @param phone 目标手机号
     * @return true 表示在限流阈值内允许发送，false 表示触发限流拦截
     */
    boolean tryAcquire(String phone);

    /**
     * 强校验获取发信许可，超限时抛出结构化 SmsRateLimitException
     *
     * @param phone 目标手机号
     * @throws SmsRateLimitException 频控超限异常
     */
    void acquire(String phone);

    /**
     * 重置指定手机号的频控状态（用于测试断言或运维封禁解除）
     *
     * @param phone 目标手机号
     */
    void reset(String phone);
}
