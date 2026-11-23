package io.github.atengk.boot.sample.rabbitmq.dto;

import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.rabbitmq.model.OrderCreateMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批量多租户混排订单投递请求 DTO
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Data
public class BatchTenantRequest {

    /**
     * 混排租户与上下文状态的订单条目列表
     */
    @Valid
    @NotEmpty(message = "批量订单列表不能为空")
    private ValidList<BatchTenantOrderItem> items;

    /**
     * 单条带独立上下文声明的订单请求项
     */
    @Data
    public static class BatchTenantOrderItem {

        /**
         * 订单业务编号
         */
        @NotBlank(message = "订单编号不能为空")
        private String orderId;

        /**
         * 订单金额
         */
        @NotNull(message = "订单金额不能为空")
        @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
        private BigDecimal amount;

        /**
         * 模拟发送端绑定的租户 ID（允许为 null）
         */
        private String tenantId;

        /**
         * 模拟发送端绑定的用户 ID（允许为 null）
         */
        private String userId;

        /**
         * 是否忽略多租户过滤
         */
        private boolean ignoreTenant;

        /**
         * 转换为消息契约对象
         *
         * @return 订单创建业务消息
         */
        public OrderCreateMessage toMessage() {
            return new OrderCreateMessage(
                    orderId,
                    amount,
                    userId != null ? userId : "guest",
                    LocalDateTime.now()
            );
        }
    }
}
