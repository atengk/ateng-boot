package io.github.atengk.boot.pay.model.request;

import io.github.atengk.boot.pay.enums.PayChannel;
import io.github.atengk.boot.pay.enums.PayTradeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 顶层统一收单请求入参
 * <p>
 * 抹平微信支付与支付宝渠道差异，通过 {@link #channel} 自动分发路由，
 * 封装全场景收单参数与服务商 ISV 模式字段，全域恪守 {@link BigDecimal}（元）强类型契约。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedPayRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标支付渠道 (WECHAT_PAY, ALIPAY，未传时优先从 PayContextHolder 获取)
     */
    private PayChannel channel;

    /**
     * 交易场景类型 (NATIVE, JSAPI, H5, APP, BARCODE, PAGE)
     */
    private PayTradeType tradeType;

    /**
     * 商户订单号 (必填，商户系统内部全局唯一订单号)
     */
    private String outTradeNo;

    /**
     * 订单标题 / 商品描述 (必填，如 "iPhone 16 Pro 256G")
     */
    private String subject;

    /**
     * 订单副描述 (可选，若未指定 subject 则尝试回退使用 description)
     */
    private String description;

    /**
     * 订单金额 (单位：元，全域强类型 BigDecimal，小数位严禁超过 2 位)
     */
    private BigDecimal amount;

    /**
     * 货币类型 (默认 CNY)
     */
    @Builder.Default
    private String currency = "CNY";

    /**
     * 商户配置别名或应用别名 (用于多商户/多应用路由，未传时走默认级联策略)
     */
    private String merchantKey;

    /**
     * 异步通知回调地址 (Webhook URL，可选，未传时使用配置项)
     */
    private String notifyUrl;

    /**
     * 网页支付同步跳转返回地址 (Return URL，仅支付宝 Page/Wap 或特定场景使用)
     */
    private String returnUrl;

    /**
     * 付款码条码 (条码付 BARCODE 场景必填，18~24 位纯数字)
     */
    private String authCode;

    /**
     * 用户在商户应用下的唯一标识 OpenID (微信 JSAPI 场景必填)
     */
    private String openid;

    /**
     * 用户在子商户应用下的 OpenID (微信服务商 JSAPI 场景专用)
     */
    private String subOpenid;

    /**
     * 附加数据 / 回传数据 (在查询 API 和支付通知中原样返回)
     */
    private String attach;

    /**
     * 订单详情扩展描述
     */
    private String body;

    /**
     * 订单绝对超时时间 (微信 RFC3339 格式如 "2026-10-23T23:59:59+08:00"，支付宝格式如 "yyyy-MM-dd HH:mm:ss")
     */
    private String timeExpire;

    /**
     * 订单相对超时时间 (如 "30m", "15d" 等，默认 "30m")
     */
    @Builder.Default
    private String timeoutExpress = "30m";

    // ================= 服务商 (ISV) 模式属性 =================

    /**
     * 服务商商户号 (微信服务商专用)
     */
    private String spMchId;

    /**
     * 特约子商户号 (微信服务商专用，sub_mchid)
     */
    private String subMchId;

    /**
     * 服务商应用 AppId (微信服务商专用)
     */
    private String spAppId;

    /**
     * 特约子商户应用 AppId (微信服务商可选，sub_appid)
     */
    private String subAppId;

    /**
     * 支付宝第三方应用授权令牌 (支付宝 ISV 特约商户代调用专用，app_auth_token)
     */
    private String appAuthToken;
}
