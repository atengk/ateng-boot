package io.github.atengk.boot.sample.rabbitmq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 延迟超时订单请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDelayRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单业务编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderId;

    /**
     * 超时时长 (秒)
     */
    @NotNull(message = "超时时长不能为空")
    @Positive(message = "超时时长必须大于0")
    private Long timeoutSeconds;

    /**
     * 超时取消原因
     */
    @NotBlank(message = "超时取消原因不能为空")
    private String reason;
}
