package io.github.atengk.boot.sample.kafka.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 阶梯延迟重试触发请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryTriggerRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待触发重试的订单业务ID
     */
    @NotBlank(message = "订单业务ID不能为空")
    private String orderId;
}
