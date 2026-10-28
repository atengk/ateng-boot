package io.github.atengk.boot.sample.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * API 加签验签订单载荷对象
 * 配合 @ApiSign 注解用于请求体防篡改与签名计算
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
public class OrderSignDTO {

    /**
     * 业务订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /**
     * 订单交易金额
     */
    @NotNull(message = "订单金额不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于0")
    private BigDecimal amount;
}
