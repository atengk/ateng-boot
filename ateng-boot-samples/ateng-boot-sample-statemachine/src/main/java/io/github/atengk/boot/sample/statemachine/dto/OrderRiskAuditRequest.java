package io.github.atengk.boot.sample.statemachine.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单风控审核请求 DTO（Choice 动态分支路由）
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRiskAuditRequest {

    /**
     * 订单主键 ID
     */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /**
     * 风控评分（>= 80 拦截至退款状态，否则放行至待发货）
     */
    @NotNull(message = "风控评分不能为空")
    private Integer riskScore;
}
