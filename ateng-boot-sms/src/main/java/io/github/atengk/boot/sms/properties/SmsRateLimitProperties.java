package io.github.atengk.boot.sms.properties;

import lombok.Data;

import java.io.Serializable;

/**
 * 短信滑动窗口频控防刷配置属性
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Data
public class SmsRateLimitProperties implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 单手机号每分钟发信上限，默认 1 条
     */
    private int perMinute = 1;

    /**
     * 单手机号每小时发信上限，默认 5 条
     */
    private int perHour = 5;

    /**
     * 单手机号每天发信上限，默认 10 条
     */
    private int perDay = 10;
}
