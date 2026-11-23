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
import java.util.Map;

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

    /**
     * 自定义扩展属性（支持延时/定时消息透传与 SQL92 属性过滤）
     */
    private Map<String, String> properties;

    private static final long DEFAULT_DELAY_SECONDS = 10L;
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long DEFAULT_FUTURE_MILLIS = 10_000L;
    private static final String DEFAULT_STATUS = "UNPAID";

    /**
     * 解析相对延时秒数，未显式传入或非正数时回退至默认值（10 秒）
     *
     * @return 延时秒数
     */
    public long resolveDelaySeconds() {
        return delaySeconds != null && delaySeconds > 0 ? delaySeconds : DEFAULT_DELAY_SECONDS;
    }

    /**
     * 解析目标定时投递的毫秒级时间戳
     *
     * @return 未来投递时间戳
     */
    public long resolveDeliverAtTimestamp() {
        long now = System.currentTimeMillis();
        if (futureSeconds != null && futureSeconds > 0) {
            return now + futureSeconds * MILLIS_PER_SECOND;
        }
        if (deliverAtTimestamp != null && deliverAtTimestamp > now) {
            return deliverAtTimestamp;
        }
        return now + DEFAULT_FUTURE_MILLIS;
    }

    /**
     * 转换为标准订单领域消息对象
     *
     * @return 订单消息实体
     */
    public OrderMessage toOrderMessage() {
        return OrderMessage.builder()
                .orderId(orderId)
                .amount(amount)
                .customerName(customerName)
                .status(status != null ? status : DEFAULT_STATUS)
                .build();
    }
}
