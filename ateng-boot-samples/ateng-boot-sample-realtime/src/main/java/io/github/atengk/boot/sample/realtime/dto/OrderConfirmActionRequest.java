package io.github.atengk.boot.sample.realtime.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 上行订单确认动作强类型载荷对象（演示 @RealtimeAction 自动 JSR-303 校验）
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderConfirmActionRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单唯一编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderId;

    /**
     * 确认商品数量
     */
    @NotNull(message = "购买数量不能为 null")
    @Min(value = 1, message = "购买数量至少为 1")
    private Integer quantity;

    /**
     * 可选备注说明
     */
    private String remark;
}
