package io.github.atengk.boot.sample.statemachine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单发货请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderShipRequest {

    /**
     * 订单主键 ID
     */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /**
     * 物流运单号
     */
    @NotBlank(message = "物流运单号不能为空")
    private String trackingNo;
}
