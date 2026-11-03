package io.github.atengk.boot.sample.liteflow.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 电商复合营销算价请求参数
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class PriceCalculationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务订单标识
     */
    @NotBlank(message = "订单编号 (orderId) 不能为空")
    private String orderId;

    /**
     * 用户唯一标识
     */
    @NotBlank(message = "用户标识 (userId) 不能为空")
    private String userId;

    /**
     * 订单原始商品总金额
     */
    @NotNull(message = "原始金额 (originalAmount) 不能为空")
    @DecimalMin(value = "0.01", message = "原始金额必须大于 0")
    private BigDecimal originalAmount;

    /**
     * 选用优惠券标识（可选）
     */
    private String couponId;
}
