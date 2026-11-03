package io.github.atengk.boot.sample.liteflow.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 订单履约执行请求参数
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class OrderFulfillmentRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务订单标识
     */
    @NotBlank(message = "订单编号 (orderId) 不能为空")
    private String orderId;

    /**
     * 目标商品 SKU 编号
     */
    @NotBlank(message = "商品 SKU (skuId) 不能为空")
    private String skuId;

    /**
     * 履约扣减数量
     */
    @NotNull(message = "履约数量 (quantity) 不能为空")
    @Min(value = 1, message = "履约数量必须大于或等于 1")
    private Integer quantity;

    /**
     * 是否模拟后续发货故障（用于演示前序节点逆序 rollback 补偿）
     */
    private Boolean simulateFailure;
}
