package io.github.atengk.boot.pay.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 支付宝专有收单与操作请求入参
 * <p>
 * 原生支持电脑网站（Page）、手机网站（Wap）、App 客户端、当面付条码与扫码全场景入参定制，
 * 支持服务商（ISV）第三方应用授权令牌 {@code appAuthToken} 与全域强类型元金额契约。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlipayPayRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付宝应用配置别名或 AppId (用于多应用级联匹配，未传时优先取上下文或默认应用)
     */
    private String appKey;

    /**
     * 支付宝开放平台分配的应用 AppId (可选，未传时取解析出的应用配置)
     */
    private String appId;

    /**
     * 商户订单号 (必填，商户系统内部全局唯一订单号)
     */
    private String outTradeNo;

    /**
     * 支付宝交易号 (查询、关单、撤销订单时可选)
     */
    private String tradeNo;

    /**
     * 订单标题 / 商品标题 (收单场景必填，如 "iPhone 16 Pro 256G")
     */
    private String subject;

    /**
     * 订单金额 (单位：元，如 10.50，全域强类型 BigDecimal)
     */
    private BigDecimal amount;

    /**
     * 异步通知回调地址 (Webhook URL，可选，未传时取应用配置的 notifyUrl)
     */
    private String notifyUrl;

    /**
     * 网页支付同步跳转返回地址 (Return URL，Page/Wap 常用，未传时取应用配置的 returnUrl)
     */
    private String returnUrl;

    /**
     * 付款码条码 (当面付条码收单 BARCODE 场景必填，18~24 位纯数字)
     */
    private String authCode;

    /**
     * 订单附加描述 / 商品详细描述 (可选)
     */
    private String body;

    /**
     * 订单绝对超时时间 (格式为 "yyyy-MM-dd HH:mm:ss")
     */
    private String timeExpire;

    /**
     * 该笔订单允许的最晚付款时间，逾期将关闭交易 (如 "30m", "15d", "1c" 等，默认 "30m")
     */
    @Builder.Default
    private String timeoutExpress = "30m";

    /**
     * 电脑网站支付 PC 扫码支付方式 (0: 前置模式-展示二维码; 1: 前置模式-展示二维码和跳回按钮; 2: 极简模式等)
     */
    private String qrPayMode;

    /**
     * 电脑网站支付自定义二维码宽度 (单位：像素)
     */
    private Long qrcodeWidth;

    /**
     * 公用回传参数 (如果业务方需要在异步通知中原样回传，格式可为 urlencode 编码字符串)
     */
    private String passbackParams;

    // ================= 服务商 (ISV) 模式属性 =================

    /**
     * 第三方应用授权令牌 (服务商代特约商家发起交易时的令牌，app_auth_token)
     */
    private String appAuthToken;
}
