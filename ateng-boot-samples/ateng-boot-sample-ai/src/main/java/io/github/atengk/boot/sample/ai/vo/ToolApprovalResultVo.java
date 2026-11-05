package io.github.atengk.boot.sample.ai.vo;

import io.github.atengk.boot.ai.tool.approval.ToolApprovalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 人机协作审批裁决结果视图对象
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolApprovalResultVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工单标识
     */
    private String ticketId;

    /**
     * 最终审批状态
     */
    private ToolApprovalStatus status;

    /**
     * 是否执行成功
     */
    private Boolean success;

    /**
     * 工具重放执行成功输出或返回值反序列化对象
     */
    private Object result;

    /**
     * 拒绝原因或执行异常信息
     */
    private String errorMessage;

    /**
     * 重放执行耗时（毫秒）
     */
    private Long executionTimeMs;
}
