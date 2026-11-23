package io.github.atengk.boot.sample.liteflow.context;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 电商复合营销算价业务上下文
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class PriceCalculationContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务订单标识
     */
    private String orderId;

    /**
     * 用户唯一标识
     */
    private String userId;

    /**
     * 原始商品总金额
     */
    private BigDecimal originalAmount;

    /**
     * 选用优惠券标识
     */
    private String couponId;

    /**
     * 优惠券抵扣金额
     */
    private BigDecimal couponDiscount = BigDecimal.ZERO;

    /**
     * 用户会员等级 (NORMAL, VIP, SVIP)
     */
    private String userLevel;

    /**
     * 会员等级折扣抵扣金额
     */
    private BigDecimal levelDiscount = BigDecimal.ZERO;

    /**
     * 基础运费金额
     */
    private BigDecimal postage = BigDecimal.ZERO;

    /**
     * 最终应付金额
     */
    private BigDecimal finalAmount;
}
