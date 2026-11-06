package io.github.atengk.boot.sample.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对抗式辩论人工裁决断点恢复请求 DTO
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateResumeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作流执行会话标识
     */
    @NotBlank(message = "会话标识 threadId 不能为空")
    private String threadId;

    /**
     * 人工裁决结论 (如 APPROVED / REJECTED / COMPROMISE)
     */
    @NotBlank(message = "裁决动作 action 不能为空 (APPROVED / REJECTED / COMPROMISE)")
    private String action;

    /**
     * 裁决人批注与仲裁意见
     */
    private String arbiterComment;
}
