package io.github.atengk.boot.sample.rocketmq.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单领域业务消息实体
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage {

    @NotBlank(message = "订单ID不能为空")
    private String orderId;

    @PositiveOrZero(message = "订单金额不能为负数")
    private Double amount;

    private String customerName;

    private String status;
}
