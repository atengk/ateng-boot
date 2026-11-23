package io.github.atengk.boot.sample.rabbitmq.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 优先级订单请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriorityOrderRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单信息
     */
    @Valid
    @NotNull(message = "订单信息不能为空")
    private OrderCreateRequest order;

    /**
     * 消息优先级 (0-10)
     */
    @Min(value = 0, message = "优先级最低为0")
    @Max(value = 10, message = "优先级最高为10")
    private int priority;
}
