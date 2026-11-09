package io.github.atengk.boot.sample.kafka.dto;

import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单消息投递请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProduceRequest implements Serializable {

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
    @DecimalMin(value = "0.01", message = "订单金额必须大于0")
    private BigDecimal amount;

    /**
     * 下单用户ID
     */
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /**
     * 租户ID (选填)
     */
    private String tenantId;

    /**
     * 指定物理分区编号 (选填，为空时由 Key Murmur2 哈希路由)
     */
    private Integer partition;

    /**
     * 自定义分区路由 Key (选填，为空时默认使用 orderId)
     */
    private String key;

    /**
     * 转换为订单领域实体
     *
     * @return 订单传输消息
     */
    public OrderMessage toOrderMessage() {
        return new OrderMessage(orderId, amount, userId, tenantId);
    }
}
