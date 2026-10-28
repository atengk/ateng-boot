package io.github.atengk.boot.sample.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 流量控制与幂等防重订单请求载荷
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
public class TrafficOrderDTO {

    /**
     * 业务订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /**
     * 订单金额
     */
    private BigDecimal amount;
}
