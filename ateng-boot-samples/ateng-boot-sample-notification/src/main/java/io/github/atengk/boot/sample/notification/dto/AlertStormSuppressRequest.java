package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 告警风暴模拟请求传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
public class AlertStormSuppressRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务订单标识
     */
    @NotBlank(message = "业务订单标识不能为空")
    private String orderId = "ORD-STORM-001";

    /**
     * 模拟密码凭据（用于验证切面入参安全掩码脱敏）
     */
    @NotBlank(message = "密码凭据不能为空")
    private String password = "STORM_SECRET_PWD_123";

    /**
     * 模拟瞬时并发调用总次数
     */
    @Min(value = 1, message = "并发触发次数至少为 1")
    @Max(value = 500, message = "单次模拟并发上限不超过 500")
    private Integer count = 10;
}
