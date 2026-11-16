package io.github.atengk.boot.sample.observability.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单结算请求传输对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSettleRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderId;

    /**
     * 结算金额
     */
    @NotNull(message = "结算金额不能为空")
    @DecimalMin(value = "0.01", message = "结算金额必须大于 0")
    private Double amount;

    /**
     * 支付方式（如 alipay、wechat、bank）
     */
    @NotBlank(message = "支付方式不能为空")
    private String paymentMethod;
}
