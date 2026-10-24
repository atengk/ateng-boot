package io.github.atengk.boot.pay.model.request;

import io.github.atengk.boot.pay.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 顶层统一退款查询请求入参
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedRefundQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标支付渠道 (WECHAT_PAY, ALIPAY，未传时优先从 PayContextHolder 获取)
     */
    private PayChannel channel;

    /**
     * 商户订单号 (原交易商户订单号，支付宝退款查询必传或建议传入)
     */
    private String outTradeNo;

    /**
     * 渠道交易流水号 (可选)
     */
    private String transactionId;

    /**
     * 商户退款单号 (本次退款在商户内部的单号，必填)
     */
    private String outRefundNo;

    /**
     * 商户配置别名或应用别名 (可选，用于多商户/多应用路由)
     */
    private String merchantKey;

    /**
     * 服务商模式特约子商户号 (微信 sub_mchid)
     */
    private String subMchId;

    /**
     * 服务商模式第三方应用授权令牌 (支付宝 app_auth_token)
     */
    private String appAuthToken;
}
