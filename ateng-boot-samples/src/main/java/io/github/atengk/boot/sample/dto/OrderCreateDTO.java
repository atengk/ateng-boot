package io.github.atengk.boot.sample.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 示例订单创建请求 DTO
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateDTO {

    /**
     * 商户业务唯一订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /**
     * 支付金额
     */
    @NotNull(message = "支付金额不能为空")
    private BigDecimal amount;
}
