package io.github.atengk.boot.pay.model.request;

import io.github.atengk.boot.pay.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一资金转账状态查询请求参数
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedTransferQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标支付渠道
     */
    private PayChannel channel;

    /**
     * 商户配置键或应用别名（可选）
     */
    private String merchantKey;

    /**
     * 商户转账唯一单号 / 商家批次单号
     */
    private String outBizNo;

    /**
     * 渠道转账订单号（微信 batchId 或 支付宝 orderId，与 outBizNo 二选一）
     */
    private String orderId;

    /**
     * 渠道资金流水号（支付宝 payFundOrderId 或 微信 detailId）
     */
    private String payFundOrderId;

    /**
     * 支付宝销售产品码（可选，默认 TRANS_ACCOUNT_NO_PWD）
     */
    private String productCode;

    /**
     * 支付宝业务场景（可选，默认 DIRECT_TRANSFER）
     */
    private String bizScene;
}
