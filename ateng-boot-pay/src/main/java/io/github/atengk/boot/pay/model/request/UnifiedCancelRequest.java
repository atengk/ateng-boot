package io.github.atengk.boot.pay.model.request;

import io.github.atengk.boot.pay.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 顶层统一撤销/冲正请求入参
 * <p>
 * 支持根据支付渠道 {@link #channel} 自动分发至专有门面执行超时未支付订单撤销或冲正。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedCancelRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标支付渠道 (WECHAT_PAY, ALIPAY，未传时优先从 PayContextHolder 获取)
     */
    private PayChannel channel;

    /**
     * 商户订单号 (与渠道交易号至少提供其一)
     */
    private String outTradeNo;

    /**
     * 渠道交易流水号
     */
    private String transactionId;

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
