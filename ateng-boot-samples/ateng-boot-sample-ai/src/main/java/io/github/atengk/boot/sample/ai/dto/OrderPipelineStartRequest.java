package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单履约流水线启动请求 DTO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPipelineStartRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务订单编号
     */
    @NotBlank(message = "订单编号 orderId 不能为空")
    private String orderId;

    /**
     * 订单交易金额
     */
    @NotNull(message = "订单金额 amount 不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
    private BigDecimal amount;

    /**
     * 商品品类 (PHYSICAL: 实物商品 / VIRTUAL: 虚拟卡密)
     */
    @NotBlank(message = "商品品类 itemType 不能为空")
    private String itemType;

    /**
     * 买家信用评分 (300-900)
     */
    @NotNull(message = "买家信用评分 creditScore 不能为空")
    @Min(value = 300, message = "信用评分最低为 300")
    @Max(value = 900, message = "信用评分最高为 900")
    private Integer creditScore;

    /**
     * 是否启用 VIP 特权跳过风控审核
     */
    private Boolean vipBypass;

    /**
     * 订单备注说明
     */
    private String remarks;
}
