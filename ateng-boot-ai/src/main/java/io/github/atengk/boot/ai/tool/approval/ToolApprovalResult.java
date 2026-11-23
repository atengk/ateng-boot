package io.github.atengk.boot.ai.tool.approval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 高危工具审批与执行结果契约模型
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolApprovalResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工单全局唯一标识
     */
    private String ticketId;

    /**
     * 最终工单状态
     */
    private ToolApprovalStatus status;

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 反射重放执行的真实业务结果对象（当成功执行时非空）
     */
    private Object result;

    /**
     * 失败或异常错误描述
     */
    private String errorMessage;

    /**
     * 执行耗时 (毫秒)
     */
    private Long executionTimeMs;

    public static ToolApprovalResult success(String ticketId, Object result, Long executionTimeMs) {
        return ToolApprovalResult.builder()
                .ticketId(ticketId)
                .status(ToolApprovalStatus.APPROVED)
                .success(true)
                .result(result)
                .executionTimeMs(executionTimeMs)
                .build();
    }

    public static ToolApprovalResult rejected(String ticketId, String reason) {
        return ToolApprovalResult.builder()
                .ticketId(ticketId)
                .status(ToolApprovalStatus.REJECTED)
                .success(false)
                .errorMessage(reason)
                .executionTimeMs(0L)
                .build();
    }

    public static ToolApprovalResult failed(String ticketId, String errorMessage, Long executionTimeMs) {
        return ToolApprovalResult.builder()
                .ticketId(ticketId)
                .status(ToolApprovalStatus.APPROVED)
                .success(false)
                .errorMessage(errorMessage)
                .executionTimeMs(executionTimeMs)
                .build();
    }
}
