package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Kubernetes 优雅下线发版静默演练请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
public class GracefulDeregisterRequest {

    /**
     * 目标实例 ID
     */
    @NotBlank(message = "实例 ID 不能为空")
    private String instanceId;

    /**
     * 注销后可选紧跟模拟的后续状态（如 OFFLINE，用于验证 60s 发版静默窗口拦截效果）
     */
    @Pattern(
            regexp = "^(?i)(DOWN|OFFLINE|UNKNOWN)$",
            message = "后续故障状态仅支持 DOWN、OFFLINE 或 UNKNOWN"
    )
    private String followUpFaultStatus;
}
