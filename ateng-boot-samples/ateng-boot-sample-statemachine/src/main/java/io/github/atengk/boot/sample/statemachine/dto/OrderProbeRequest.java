package io.github.atengk.boot.sample.statemachine.dto;

import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单预检探测请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProbeRequest {

    /**
     * 订单主键 ID
     */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /**
     * 拟触发的流转事件
     */
    @NotNull(message = "流转事件不能为空")
    private OrderEvent event;
}
