package io.github.atengk.boot.sample.rocketmq.dto;

import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 事务订单发送请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTransactionRequest {

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
     * 本地事务执行决断（true: COMMIT / 提交成功; false: ROLLBACK / 触发回滚）
     */
    private Boolean localSuccess;

    public OrderMessage toOrderMessage() {
        return OrderMessage.builder()
                .orderId(orderId)
                .amount(amount)
                .customerName(customerName)
                .status(status != null ? status : "TX_PENDING")
                .build();
    }
}
