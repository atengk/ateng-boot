package io.github.atengk.boot.sample.redis.model.delay;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单超时延时取消任务投递请求参数
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDelaySendRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    @NotBlank(message = "订单编号不能为空")
    private String orderId;

    /**
     * 延时秒数
     */
    @NotNull(message = "延时秒数不能为空")
    @Min(value = 0, message = "延时秒数不能小于 0")
    private Long delaySeconds;

    /**
     * 超时取消原因
     */
    private String reason;
}
