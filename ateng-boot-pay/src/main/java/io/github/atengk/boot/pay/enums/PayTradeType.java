package io.github.atengk.boot.pay.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付交易场景类型枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum PayTradeType {

    /**
     * Native 扫码支付（生成静态或动态二维码由用户扫描）
     */
    NATIVE("NATIVE", "Native扫码支付"),

    /**
     * JSAPI / 小程序 / 公众号网页支付
     */
    JSAPI("JSAPI", "JSAPI/小程序支付"),

    /**
     * H5 手机外部浏览器跳转支付
     */
    H5("H5", "H5手机网页支付"),

    /**
     * App 客户端调起 SDK 支付
     */
    APP("APP", "App客户端支付"),

    /**
     * 条码支付 / 当面付（商户扫描用户手机付款码，MicroPay）
     */
    BARCODE("BARCODE", "付款码条码支付"),

    /**
     * 电脑网站支付（PC 端 Web 浏览器跳转收银台）
     */
    PAGE("PAGE", "电脑网站支付");

    /**
     * 场景代码
     */
    private final String code;

    /**
     * 场景描述
     */
    private final String description;
}
