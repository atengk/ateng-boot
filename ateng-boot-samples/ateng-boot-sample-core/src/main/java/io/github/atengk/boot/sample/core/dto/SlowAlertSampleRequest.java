package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 慢调用领域事件与启动慢 Bean 诊断场景请求传输对象
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowAlertSampleRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务唯一业务标识
     */
    @NotBlank(message = "任务标识不能为空")
    private String taskId;

    /**
     * 敏感访问凭据（用于验证 SlowAlertEvent.argsSummary 自动脱敏掩码）
     */
    @NotBlank(message = "敏感凭据不能为空")
    private String secretToken;
}
