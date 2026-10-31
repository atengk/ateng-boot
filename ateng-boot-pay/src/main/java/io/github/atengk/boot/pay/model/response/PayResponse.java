package io.github.atengk.boot.pay.model.response;

import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayTradeState;
import io.github.atengk.boot.pay.enums.PayTradeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 扁平化统一收单与凭据响应出参
 * <p>
 * 统一承载多渠道（微信、支付宝）及全场景（Native、JSAPI、H5、App、条码付）的交易凭据与状态，
 * 前端与调用方直接按需消费扁平凭据字段（如 {@code qrCode}、{@code payUrl}、{@code wechatJsapi} 等），
 * 彻底消除传统多态强转与 ClassCastException 隐患。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道 (WECHAT_PAY, ALIPAY)
     */
    private PayChannel channel;

    /**
     * 交易场景类型 (NATIVE, JSAPI, H5, APP, BARCODE)
     */
    private PayTradeType tradeType;

    /**
     * 商户订单号 (业务方出站单号)
     */
    private String outTradeNo;

    /**
     * 渠道交易流水号 (微信 TransactionId, 支付宝 TradeNo)
     */
    private String transactionId;

    /**
     * 支付宝交易号 (trade_no，专用于支付宝小程序 my.tradePay({ tradeNO: res.data.tradeNo }))
     */
    private String tradeNo;

    /**
     * 预支付交易会话标识 (prepay_id)
     */
    private String prepayId;

    /**
     * 是否受理或支付成功
     */
    private boolean success;

    /**
     * 标准化交易生命周期状态枚举
     */
    private PayTradeState tradeState;

    /**
     * 渠道原生交易状态码 (如 SUCCESS, TRADE_SUCCESS, USERPAYING 等)
     */
    private String rawTradeState;

    /**
     * 交易状态描述或失败原因
     */
    private String tradeStateDesc;

    /**
     * 订单金额 (单位：元，全域强类型 BigDecimal)
     */
    private BigDecimal amount;

    // ================= 扁平化直达凭据字段 =================

    /**
     * Native 扫码支付二维码内容链接 (code_url)
     */
    private String qrCode;

    /**
     * H5 手机网页跳转支付 URL (h5_url)
     */
    private String payUrl;

    /**
     * 电脑网站/手机网站支付自提交 HTML 表单脚本 (formHtml，支付宝为主)
     */
    private String formHtml;

    /**
     * App 调起串 (orderString，支付宝 App 支付或通用串)
     */
    private String orderString;

    /**
     * 微信小程序 / 公众号 JSAPI 调起 5 元素凭据包 (直接传给 wx.requestPayment)
     */
    private WechatJsapiParams wechatJsapi;

    /**
     * 微信 App 客户端调起凭据包 (直接传给开放平台 App SDK)
     */
    private WechatAppParams wechatApp;

    /**
     * 底层渠道 SDK 原始响应对象 (逃生舱，便于特殊高阶属性透传读取)
     */
    private Object rawResponse;
}
