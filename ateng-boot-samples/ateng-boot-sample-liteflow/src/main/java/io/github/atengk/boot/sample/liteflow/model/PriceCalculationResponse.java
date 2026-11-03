package io.github.atengk.boot.sample.liteflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 电商复合营销算价结果
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceCalculationResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务订单标识
     */
    private String orderId;

    /**
     * 原始商品总金额
     */
    private BigDecimal originalAmount;

    /**
     * 优惠券抵扣金额
     */
    private BigDecimal couponDiscount;

    /**
     * 用户会员等级折扣抵扣金额
     */
    private BigDecimal levelDiscount;

    /**
     * 基础运费金额
     */
    private BigDecimal postage;

    /**
     * 最终应付金额
     */
    private BigDecimal finalAmount;

    /**
     * 规则执行轨迹摘要
     */
    private String traceSummary;
}
