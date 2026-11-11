package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 告警状态跃迁演练触发请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
public class StatusTransitionTriggerRequest {

    /**
     * 目标实例 ID
     */
    @NotBlank(message = "实例 ID 不能为空")
    private String instanceId;

    /**
     * 跃迁目标状态（UP/DOWN/OFFLINE/UNKNOWN）
     */
    @NotBlank(message = "目标状态不能为空")
    @Pattern(
            regexp = "^(?i)(UP|DOWN|OFFLINE|UNKNOWN)$",
            message = "目标状态仅支持 UP、DOWN、OFFLINE 或 UNKNOWN"
    )
    private String status;

    /**
     * 模拟故障探针组件名称（如 diskSpace、redis，可选）
     */
    private String probeComponent;

    /**
     * 模拟故障探针错误根因描述（可选）
     */
    private String probeError;

    /**
     * 是否立即触发防抖告警（true 时临时采用 0s 防抖窗口以便实时演示告警卡片生成）
     */
    private Boolean immediate;
}
