package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单风控审核流程启动请求 DTO
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReviewStartRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务订单唯一编号
     */
    @NotBlank(message = "订单编号 orderId 不能为空")
    private String orderId;

    /**
     * 订单金额
     */
    @NotNull(message = "订单金额 amount 不能为空")
    @Positive(message = "订单金额必须大于 0")
    private BigDecimal amount;

    /**
     * 用户信用评分 (300 ~ 850)
     */
    @NotNull(message = "信用评分 creditScore 不能为空")
    @Min(value = 300, message = "信用评分不能低于 300")
    @Max(value = 850, message = "信用评分不能高于 850")
    private Integer creditScore;

    /**
     * 业务备注信息
     */
    private String remarks;
}
