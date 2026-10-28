package io.github.atengk.boot.pay.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 微信支付专有收单与操作请求入参
 * <p>
 * 原生支持直连商户与服务商模式（特约子商户），支持全场景入参定制与强类型元金额契约。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WechatPayRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商户配置别名或商户号 (用于多商户级联匹配，未传时优先取上下文或默认商户)
     */
    private String merchantKey;

    /**
     * 微信商户号 (MchId，可选，未传时取解析出的商户配置)
     */
    private String mchId;

    /**
     * 微信关联应用 AppId (可选，未传时取解析出的商户配置默认 appId)
     */
    private String appId;

    /**
     * 商户订单号 (必填，商户系统内部全局唯一订单号)
     */
    private String outTradeNo;

    /**
     * 微信支付订单号 (查询订单时可选)
     */
    private String transactionId;

    /**
     * 商品描述 / 标价标题 (收单场景必填，如 "iPhone 16 Pro 256G")
     */
    private String description;

    /**
     * 订单金额 (单位：元，如 10.50，全域强类型 BigDecimal)
     */
    private BigDecimal amount;

    /**
     * 货币类型 (默认 CNY)
     */
    @Builder.Default
    private String currency = "CNY";

    /**
     * 异步通知回调地址 (Webhook URL，可选，未传时取商户配置的 notifyUrl)
     */
    private String notifyUrl;

    /**
     * 用户在直连商户/服务商应用下的唯一标识 OpenID (JSAPI 收单必填)
     */
    private String openid;

    /**
     * 付款码条码 (条码付 BARCODE 场景必填，18 位纯数字)
     */
    private String authCode;

    /**
     * 用户终端 IP (H5 场景必填或建议传入)
     */
    private String payerClientIp;

    /**
     * H5 场景类型 (如 "Wap", "iOS", "Android"，默认 "Wap")
     */
    @Builder.Default
    private String h5Type = "Wap";

    /**
     * 附加数据 (在查询 API 和支付通知中原样返回，可存放业务租户或私有标记)
     */
    private String attach;

    /**
     * 订单失效时间 (遵循 RFC3339 标准格式，如 "2026-10-23T23:59:59+08:00")
     */
    private String timeExpire;

    /**
     * 订单优惠标记 (代金券或立减优惠功能的 goods_tag)
     */
    private String goodsTag;

    /**
     * 是否指定分账 (可选，默认 false)
     * <p>
     * <b>特别警告：</b>
     * 微信支付分账要求必须在统一下单前置打标（传入 true）。若未打标，后续调用分账 API 将报错。
     * 若打标为 true，订单支付成功后资金将处于冻结状态；必须在分账完成后主动调用完结分账解冻接口（unfreeze），
     * 否则剩余在途资金将被长期冻结导致商户资金沉淀无法结算提现。
     * </p>
     */
    private Boolean profitSharing;

    // ================= 服务商 (ISV) 模式属性 =================

    /**
     * 服务商商户号 (服务商模式专用)
     */
    private String spMchId;

    /**
     * 服务商应用 AppId (服务商模式专用)
     */
    private String spAppId;

    /**
     * 特约子商户号 (服务商模式专用，sub_mchid)
     */
    private String subMchId;

    /**
     * 特约子商户应用 AppId (服务商模式可选，sub_appid)
     */
    private String subAppId;

    /**
     * 用户在子商户应用下的 OpenID (服务商模式 JSAPI 专用，sub_openid)
     */
    private String subOpenid;
}
