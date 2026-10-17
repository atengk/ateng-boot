package io.github.atengk.boot.sample.satoken.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单创建数据传输对象
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
public class OrderCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单业务编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderNo;

    /**
     * 订单金额
     */
    @NotNull(message = "订单金额不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
    private BigDecimal amount;
}
