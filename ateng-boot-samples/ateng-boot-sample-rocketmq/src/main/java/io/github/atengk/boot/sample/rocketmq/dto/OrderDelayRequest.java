package io.github.atengk.boot.sample.rocketmq.dto;

import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 延时与定时消息发送请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDelayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务订单号
     */
    @NotBlank(message = "订单ID不能为空")
    private String orderId;

    /**
     * 订单金额
     */
    @NotNull(message = "订单金额不能为空")
    @PositiveOrZero(message = "订单金额不能为负数")
    private Double amount;

    /**
     * 客户姓名
     */
    private String customerName;

    /**
     * 订单状态
     */
    private String status;

    /**
     * 相对延时秒数（用于 sendDelayed）
     */
    private Long delaySeconds;

    /**
     * 绝对投递时间戳（毫秒，用于 sendDeliverAt）
     */
    private Long deliverAtTimestamp;

    /**
     * 未来延时调度秒数（用于灵活计算绝对投递时间戳）
     */
    private Long futureSeconds;

    public long resolveDelaySeconds() {
        return delaySeconds != null && delaySeconds > 0 ? delaySeconds : 10L;
    }

    public long resolveDeliverAtTimestamp() {
        if (futureSeconds != null && futureSeconds > 0) {
            return System.currentTimeMillis() + futureSeconds * 1000L;
        }
        if (deliverAtTimestamp != null && deliverAtTimestamp > System.currentTimeMillis()) {
            return deliverAtTimestamp;
        }
        return System.currentTimeMillis() + 10_000L;
    }

    public OrderMessage toOrderMessage() {
        return OrderMessage.builder()
                .orderId(orderId)
                .amount(amount)
                .customerName(customerName)
                .status(status != null ? status : "UNPAID")
                .build();
    }
}
