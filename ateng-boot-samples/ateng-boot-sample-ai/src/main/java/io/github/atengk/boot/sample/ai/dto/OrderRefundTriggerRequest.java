package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单高危退款触发请求传输对象
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRefundTriggerRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待退款订单业务编号
     */
    @NotBlank(message = "订单业务编号不能为空")
    private String orderId;

    /**
     * 退款金额（元）
     */
    @NotNull(message = "退款金额不能为空")
    @DecimalMin(value = "0.01", message = "退款金额必须大于 0")
    private Double amount;

    /**
     * 退款申请原因
     */
    @NotBlank(message = "退款申请原因不能为空")
    private String reason;
}
