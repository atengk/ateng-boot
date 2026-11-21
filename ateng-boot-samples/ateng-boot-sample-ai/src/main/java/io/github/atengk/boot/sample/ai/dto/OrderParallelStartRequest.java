package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
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
 * 并行扇出订单多维核验启动请求 DTO
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderParallelStartRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务订单编号
     */
    @NotBlank(message = "订单编号 orderId 不能为空")
    private String orderId;

    /**
     * 下单用户标识
     */
    @NotBlank(message = "用户标识 userId 不能为空")
    private String userId;

    /**
     * 订单金额
     */
    @NotNull(message = "订单金额 amount 不能为空")
    @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
    private BigDecimal amount;

    /**
     * 商品 SKU 编码
     */
    @NotBlank(message = "商品 SKU skuCode 不能为空")
    private String skuCode;

    /**
     * 可选模拟风控分支延迟毫秒数（用于验证 BEST_EFFORT 单分支超时降级）
     */
    @Min(value = 0, message = "riskDelayMs 不能为负数")
    private Long riskDelayMs;
}
