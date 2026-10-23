package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 异常告警切面触发请求传输对象
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class AlertTriggerRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderId;

    /**
     * 模拟用户密码凭据（用于验证切面自动参数安全脱敏）
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 触发异常类型：npe (NullPointerException) 或 biz (BusinessException) 或 timeout (TimeoutException)
     */
    private String errorType;
}
