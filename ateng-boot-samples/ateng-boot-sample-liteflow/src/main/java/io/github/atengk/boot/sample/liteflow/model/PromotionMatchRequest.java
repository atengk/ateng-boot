package io.github.atengk.boot.sample.liteflow.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 促销决策路由匹配请求入参
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionMatchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 结算用户标识
     */
    @NotBlank(message = "userId 不能为空")
    private String userId;

    /**
     * 用户会员等级（VIP、REGULAR）
     */
    @NotBlank(message = "userLevel 不能为空")
    private String userLevel;

    /**
     * 购物车原价金额
     */
    @NotNull(message = "cartAmount 不能为空")
    @DecimalMin(value = "0.01", message = "cartAmount 必须大于 0")
    private BigDecimal cartAmount;

    /**
     * 结算商品品类（如 DIGITAL, FOOD, CLOTHING）
     */
    private String category;
}
