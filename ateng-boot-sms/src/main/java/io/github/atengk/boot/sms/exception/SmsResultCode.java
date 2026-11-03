package io.github.atengk.boot.sms.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 短信服务业务状态码枚举 (5801~5820)
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@AllArgsConstructor
public enum SmsResultCode implements IResultCode {

    /**
     * 短信发送失败
     */
    SMS_SEND_ERROR(5801, "短信发送失败"),

    /**
     * 短信发送频控超限已触发防刷拦截
     */
    SMS_RATE_LIMIT_EXCEEDED(5802, "短信发送过于频繁，请稍后重试"),

    /**
     * 验证码错误或已过期
     */
    SMS_VERIFY_CODE_INVALID(5803, "验证码错误或已过期"),

    /**
     * 验证码连续错误过多，已被锁定
     */
    SMS_VERIFY_CODE_FROZEN(5804, "验证码连续错误过多，已被锁定"),

    /**
     * 短信单日发送量达到上限
     */
    SMS_BUDGET_EXCEEDED(5805, "短信单日发送量达到上限"),

    /**
     * 手机号已被加入黑名单
     */
    SMS_BLACKLISTED(5806, "手机号已被加入黑名单"),

    /**
     * 未找到指定的短信渠道配置
     */
    SMS_CHANNEL_NOT_FOUND(5807, "未找到指定的短信渠道配置"),

    /**
     * 短信入参不合法
     */
    SMS_PARAM_INVALID(5808, "短信入参不合法");

    private final Integer code;
    private final String msg;
}
