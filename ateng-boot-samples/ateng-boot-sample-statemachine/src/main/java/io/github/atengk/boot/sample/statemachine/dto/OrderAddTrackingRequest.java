package io.github.atengk.boot.sample.statemachine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单追加物流轨迹请求 DTO（内部自跃迁）
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAddTrackingRequest {

    /**
     * 订单主键 ID
     */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /**
     * 物流运单号（若为空则复用已有运单号）
     */
    private String trackingNo;

    /**
     * 物流追加备注说明
     */
    @NotBlank(message = "物流更新说明不能为空")
    private String trackingRemark;
}
