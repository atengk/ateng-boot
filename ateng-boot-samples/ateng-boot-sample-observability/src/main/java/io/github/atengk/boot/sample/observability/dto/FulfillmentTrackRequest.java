package io.github.atengk.boot.sample.observability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单分段履约计时请求传输对象
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FulfillmentTrackRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderId;

    /**
     * 履约仓库编码
     */
    @NotBlank(message = "履约仓库编码不能为空")
    private String warehouseCode;
}
