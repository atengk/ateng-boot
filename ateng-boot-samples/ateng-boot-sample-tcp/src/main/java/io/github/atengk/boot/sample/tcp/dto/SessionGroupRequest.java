package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 设备会话群组（车队）加群/退群管理请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionGroupRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标设备唯一编号
     */
    @NotBlank(message = "设备编号 deviceId 不能为空")
    private String deviceId;

    /**
     * 分组名称
     */
    @NotBlank(message = "分组名称 group 不能为空")
    private String group;

    /**
     * 分组变更动作（JOIN 加入 / LEAVE 退出）
     */
    @NotBlank(message = "操作动作 action 不能为空")
    @Pattern(regexp = "JOIN|LEAVE", message = "action 仅支持 JOIN 或 LEAVE")
    private String action;
}
