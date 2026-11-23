package io.github.atengk.boot.sample.rocketmq.dto;

import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 订单消息发送请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProduceRequest {

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
     * 消息 Tag 标签（默认 create）
     */
    private String tag;

    /**
     * 顺序消息分组键（MessageGroup）
     */
    private String messageGroup;

    /**
     * 自定义扩展属性（支持 SQL92 属性过滤）
     */
    private Map<String, String> properties;

    public OrderMessage toOrderMessage() {
        return OrderMessage.builder()
                .orderId(orderId)
                .amount(amount)
                .customerName(customerName)
                .status(status != null ? status : "CREATED")
                .build();
    }
}
