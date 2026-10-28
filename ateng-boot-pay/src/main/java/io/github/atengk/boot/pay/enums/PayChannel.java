package io.github.atengk.boot.pay.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付渠道枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum PayChannel {

    /**
     * 微信支付
     */
    WECHAT_PAY("WECHAT_PAY", "微信支付"),

    /**
     * 支付宝
     */
    ALIPAY("ALIPAY", "支付宝");

    /**
     * 渠道代码
     */
    private final String code;

    /**
     * 渠道描述
     */
    private final String description;

    /**
     * 根据渠道代码解析枚举
     *
     * @param code 渠道代码
     * @return 支付渠道枚举，未找到返回 null
     */
    public static PayChannel fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (PayChannel channel : values()) {
            if (channel.getCode().equalsIgnoreCase(code) || channel.name().equalsIgnoreCase(code)) {
                return channel;
            }
        }
        return null;
    }
}
