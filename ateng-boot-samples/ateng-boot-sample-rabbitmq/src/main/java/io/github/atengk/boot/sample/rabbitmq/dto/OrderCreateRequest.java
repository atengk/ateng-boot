package io.github.atengk.boot.sample.rabbitmq.dto;

import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
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
import java.time.LocalDateTime;

/**
 * 订单创建请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单业务编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderId;

    /**
     * 订单金额
     */
    @NotNull(message = "订单金额不能为空")
    @Positive(message = "订单金额必须大于0")
    private BigDecimal amount;

    /**
     * 下单用户ID
     */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /**
     * 转换为订单领域消息实体
     *
     * @return 订单创建消息
     */
    public OrderCreateMessage toMessage() {
        return new OrderCreateMessage(orderId, amount, userId, LocalDateTime.now());
    }
}
