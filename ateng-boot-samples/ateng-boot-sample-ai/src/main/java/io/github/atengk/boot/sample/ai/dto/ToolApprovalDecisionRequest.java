package io.github.atengk.boot.sample.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 人机协作审批裁决请求传输对象
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolApprovalDecisionRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 审核批注或核准意见（通过时使用）
     */
    private String comment;

    /**
     * 驳回或拒绝执行原因（拒绝时使用）
     */
    private String reason;
}
