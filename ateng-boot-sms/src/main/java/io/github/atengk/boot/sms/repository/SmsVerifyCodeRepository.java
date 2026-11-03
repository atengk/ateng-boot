package io.github.atengk.boot.sms.repository;

import io.github.atengk.boot.sms.exception.SmsRateLimitException;
import io.github.atengk.boot.sms.exception.SmsVerifyCodeException;

import java.time.Duration;

/**
 * 短信验证码存储与状态核销仓储接口
 *
 * @author Ateng
 * @since 2026-11-02
 */
public interface SmsVerifyCodeRepository {

    /**
     * 前置检查是否处于重发冷却期间
     *
     * @param phone   目标手机号
     * @param bizType 业务场景标识
     * @throws SmsRateLimitException 若处于重发冷却期间，抛出频控拦截异常
     */
    default void checkCooldown(String phone, String bizType) {
    }

    /**
     * 保存验证码元数据并设置重发冷却期
     *
     * @param phone            目标手机号
     * @param bizType          业务场景标识
     * @param code             验证码明文
     * @param ttl              有效时长
     * @param resendInterval   重发冷却时间
     * @param maxRetryAttempts 最大允许错误次数
     * @throws SmsRateLimitException 若处于重发冷却期间，抛出频控拦截异常
     */
    void save(String phone, String bizType, String code, Duration ttl, Duration resendInterval, int maxRetryAttempts);

    /**
     * 校验并原子核销验证码（防重放与防暴力破解）
     * 核销成功后立即物理删除该验证码
     * 核销失败扣减剩余重试次数，连续输错达到上限自动作废并冻结
     *
     * @param phone   目标手机号
     * @param code    待核验验证码
     * @param bizType 业务场景标识
     * @return true 表示核验成功并核销
     * @throws SmsVerifyCodeException 验证码不存在、已过期、输错扣减或已冻结
     */
    boolean verify(String phone, String code, String bizType);

    /**
     * 只读核对验证码（不删除，不扣减重试次数）
     *
     * @param phone   目标手机号
     * @param code    待核对验证码
     * @param bizType 业务场景标识
     * @return true 表示验证码有效且正确
     */
    boolean check(String phone, String code, String bizType);

    /**
     * 主动作废并清除指定验证码
     *
     * @param phone   目标手机号
     * @param bizType 业务场景标识
     */
    void remove(String phone, String bizType);
}
