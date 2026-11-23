package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 电商提单责任链编排请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPipelineRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderNo;

    /**
     * 用户 ID（如 9999 会命中风控黑名单）
     */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /**
     * 订单总金额
     */
    @NotNull(message = "订单金额不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于0")
    private BigDecimal orderAmount;

    /**
     * 商品 SKU 编码
     */
    @NotBlank(message = "商品SKU不能为空")
    private String skuCode;

    /**
     * 购买数量
     */
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量至少为1")
    private Integer buyCount;

    /**
     * 当前可用库存数（若可用库存小于购买数量，则在库存阶段短路中断）
     */
    @NotNull(message = "当前库存数不能为空")
    private Integer availableStock;
}
