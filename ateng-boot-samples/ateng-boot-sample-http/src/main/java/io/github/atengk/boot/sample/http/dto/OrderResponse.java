package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单响应契约传输对象
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse implements Serializable {

    /**
     * 业务订单唯一标识号
     */
    private String orderId;

    /**
     * 订单当前处理状态（如 SUCCESS, DEGRADED, UNKNOWN）
     */
    private String status;

    /**
     * 业务提示信息或降级说明
     */
    private String message;
}
