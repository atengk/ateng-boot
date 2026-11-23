package io.github.atengk.boot.ai.tool.approval;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 高危工具审批工单生命周期状态枚举
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Getter
@AllArgsConstructor
public enum ToolApprovalStatus {

    /**
     * 待审批（已挂起，等待人工审核）
     */
    PENDING("待审批"),

    /**
     * 已批准（审批通过并已重放执行完成）
     */
    APPROVED("已批准"),

    /**
     * 已拒绝（人工拒绝执行该高危操作）
     */
    REJECTED("已拒绝"),

    /**
     * 已超时失效（超出挂起时间窗口自动废弃）
     */
    EXPIRED("已过期"),

    /**
     * 已撤销（调用方或系统主动废弃工单）
     */
    CANCELLED("已撤销");

    private final String description;
}
