package io.github.atengk.boot.sample.statemachine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 支付异步回调请求 DTO（CAS 防重与声明式幂等）
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPaymentCallbackRequest {

    /**
     * 订单主键 ID
     */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /**
     * 外部渠道支付实付金额
     */
    @NotNull(message = "支付金额不能为空")
    @Positive(message = "支付金额必须为正数")
    private BigDecimal paidAmount;

    /**
     * 渠道交易流水号
     */
    @NotBlank(message = "渠道交易流水号不能为空")
    private String channelTxNo;
}
