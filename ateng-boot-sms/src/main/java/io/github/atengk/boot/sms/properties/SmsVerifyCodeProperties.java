package io.github.atengk.boot.sms.properties;

import lombok.Data;

import java.io.Serializable;
import java.time.Duration;

/**
 * 验证码全生命周期配置属性
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
public class SmsVerifyCodeProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 验证码长度，默认 6 位
     */
    private int length = 6;

    /**
     * 验证码有效时长，默认 5 分钟
     */
    private Duration ttl = Duration.ofMinutes(5);

    /**
     * 重发冷却时间间隔，默认 60 秒
     */
    private Duration resendInterval = Duration.ofSeconds(60);

    /**
     * 最大连续核验错误上限，达到上限后验证码强制冻结作废，默认 5 次
     */
    private int maxRetryAttempts = 5;

    /**
     * 验证码字符类型：NUMERIC (纯数字), ALPHA_NUMERIC (字母数字混合), UPPER_NUMERIC (大写字母数字)
     */
    private CharType charType = CharType.NUMERIC;

    /**
     * 验证码字符集模式
     */
    public enum CharType {
        /**
         * 纯数字 0-9
         */
        NUMERIC,

        /**
         * 大小写字母与数字
         */
        ALPHA_NUMERIC,

        /**
         * 大写字母与数字
         */
        UPPER_NUMERIC
    }
}
