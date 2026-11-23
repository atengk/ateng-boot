package io.github.atengk.boot.sample.flyway.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 租户物理分库订单创建请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantOrderCreateRequest {

    /**
     * 目标租户标识（如 1001、1002、1003）
     */
    @NotBlank(message = "租户标识 (tenantId) 不能为空")
    private String tenantId;

    /**
     * 业务订单号
     */
    @NotBlank(message = "订单号 (orderNo) 不能为空")
    private String orderNo;

    /**
     * 商品名称
     */
    @NotBlank(message = "商品名称 (productName) 不能为空")
    private String productName;

    /**
     * 订单金额
     */
    @NotNull(message = "订单金额 (amount) 不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
    private BigDecimal amount;
}
