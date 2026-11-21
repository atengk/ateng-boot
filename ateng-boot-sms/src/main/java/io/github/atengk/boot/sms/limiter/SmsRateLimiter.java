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
     * 协同尝试获取手机号与客户端 IP 的发信配额许可
     *
     * @param phone 目标手机号
     * @param ip    客户端 IP（为 null 时仅校验手机号）
     * @return true 表示均在限流阈值内允许发送，false 表示触发限流拦截
     */
    default boolean tryAcquire(String phone, String ip) {
        try {
            acquire(phone, ip);
            return true;
        } catch (SmsRateLimitException e) {
            return false;
        }
    }

    /**
     * 强校验获取发信许可，超限时抛出结构化 SmsRateLimitException
     *
     * @param phone 目标手机号
     * @throws SmsRateLimitException 频控超限异常
     */
    void acquire(String phone);

    /**
     * 协同强校验获取手机号与客户端 IP 的发信许可，任一超限时抛出结构化 SmsRateLimitException
     *
     * @param phone 目标手机号
     * @param ip    客户端 IP（为 null 时仅校验手机号）
     * @throws SmsRateLimitException 频控超限异常
     */
    default void acquire(String phone, String ip) {
        acquire(phone);
        if (ip != null && !ip.isBlank()) {
            acquire("ip:" + ip.trim());
        }
    }

    /**
     * 按业务命名空间协同强校验获取手机号与客户端 IP 的发信许可（实现验证码 verify: 与通知 notify: 严格隔离）
     *
     * @param namespace 频控命名空间（如 "verify"、"notify"，可为空）
     * @param phone     目标手机号
     * @param ip        客户端 IP（为 null 时仅校验手机号）
     * @throws SmsRateLimitException 频控超限异常
     */
    default void acquire(String namespace, String phone, String ip) {
        String prefix = (namespace != null && !namespace.isBlank()) ? namespace.trim() + ":" : "";
        acquire(prefix + phone);
        if (ip != null && !ip.isBlank()) {
            acquire(prefix + "ip:" + ip.trim());
        }
    }

    /**
     * 按业务命名空间重置指定手机号的频控状态
     *
     * @param namespace 频控命名空间（如 "verify"、"notify"，可为空）
     * @param phone     目标手机号
     */
    default void reset(String namespace, String phone) {
        if (phone == null) {
            return;
        }
        String prefix = (namespace != null && !namespace.isBlank()) ? namespace.trim() + ":" : "";
        reset(prefix + phone);
    }

    /**
     * 重置指定手机号的频控状态（用于测试断言或运维封禁解除）
     *
     * @param phone 目标手机号
     */
    void reset(String phone);
}
