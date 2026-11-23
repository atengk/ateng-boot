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
 * 创建订单请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateRequest {

    /**
     * 业务唯一订单号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderSn;

    /**
     * 订单应付金额
     */
    @NotNull(message = "订单金额不能为空")
    @Positive(message = "订单金额必须为正数")
    private BigDecimal amount;
}
