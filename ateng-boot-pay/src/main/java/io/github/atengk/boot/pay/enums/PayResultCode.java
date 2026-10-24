package io.github.atengk.boot.pay.enums;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付模块专属响应状态码枚举 (58001~58020)
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public enum PayResultCode implements IResultCode {

    /**
     * 支付回调签名验证失败
     */
    PAY_SIGN_VERIFY_ERROR(58001, "支付回调签名验证失败"),

    /**
     * 支付报文解密失败
     */
    PAY_DECRYPT_ERROR(58002, "支付报文解密失败"),

    /**
     * 支付交易订单不存在
     */
    PAY_ORDER_NOT_FOUND(58003, "支付交易订单不存在"),

    /**
     * 支付交易状态异常
     */
    PAY_TRADE_STATE_INVALID(58004, "支付交易状态异常"),

    /**
     * 支付金额不合法
     */
    PAY_AMOUNT_INVALID(58005, "支付金额不合法"),

    /**
     * 支付高敏操作未授权或未开启
     */
    PAY_OPERATION_FORBIDDEN(58006, "支付高敏操作未授权或未开启"),

    /**
     * 支付网关通信异常
     */
    PAY_GATEWAY_ERROR(58007, "支付网关通信异常"),

    /**
     * 未配置指定的支付商户或应用
     */
    PAY_MERCHANT_NOT_CONFIGURED(58008, "未配置指定的支付商户或应用"),

    /**
     * 获取支付防重锁失败，请勿并发重复操作
     */
    PAY_LOCK_ACQUIRE_FAILED(58009, "获取支付防重锁失败，请勿并发重复操作"),

    /**
     * 支付请求参数非法或缺失
     */
    PAY_PARAM_INVALID(58010, "支付请求参数非法或缺失"),

    /**
     * 未找到有效的支付平台或商户证书
     */
    PAY_CERTIFICATE_NOT_FOUND(58011, "未找到有效的支付平台或商户证书"),

    /**
     * 支付相关高敏特性未启用或已被物理开关关闭
     */
    PAY_FEATURE_DISABLED(58012, "支付高敏特性未启用或已被物理开关关闭");

    /**
     * 业务状态码
     */
    private final Integer code;

    /**
     * 描述文案
     */
    private final String msg;
}
