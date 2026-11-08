package io.github.atengk.boot.sample.redis.model.stream;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单 Stream 消息流投递请求参数
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStreamSendRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderId;

    /**
     * 事件类型 (CREATED, PAID, CANCELLED, etc.)
     */
    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    /**
     * 订单金额
     */
    @NotNull(message = "订单金额不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
    private BigDecimal amount;
}
