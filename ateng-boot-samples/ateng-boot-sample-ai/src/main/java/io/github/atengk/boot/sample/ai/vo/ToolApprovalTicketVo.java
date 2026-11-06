package io.github.atengk.boot.sample.ai.vo;

import io.github.atengk.boot.ai.annotation.RiskLevel;
import io.github.atengk.boot.ai.tool.approval.ToolApprovalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 高危工具审批工单视图对象
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolApprovalTicketVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工单唯一标识
     */
    private String ticketId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 目标 Bean 类名
     */
    private String targetClassName;

    /**
     * 目标方法名
     */
    private String methodName;

    /**
     * 触发调用的智能体 Bean 名称
     */
    private String agentBeanName;

    /**
     * 触发调用的智能体接口全限定类名
     */
    private String agentInterfaceName;

    /**
     * 触发调用的智能体会话隔离标识
     */
    private String memoryId;

    /**
     * 工具执行请求标识
     */
    private String toolExecutionId;

    /**
     * 调用入参 JSON 序列化字符串
     */
    private String argumentsJson;

    /**
     * 风险等级
     */
    private RiskLevel riskLevel;

    /**
     * 业务审批类型
     */
    private String approvalType;

    /**
     * 风险与操作描述
     */
    private String description;

    /**
     * 工单当前生命周期状态
     */
    private ToolApprovalStatus status;

    /**
     * 全链路追踪链路 ID
     */
    private String traceId;

    /**
     * 多租户标识
     */
    private String tenantId;

    /**
     * 审批意见或核准批注
     */
    private String reviewComment;

    /**
     * 执行结果字符串
     */
    private String executionResultJson;

    /**
     * 异常或驳回原因
     */
    private String errorMessage;

    /**
     * 工单创建时间
     */
    private LocalDateTime createTime;

    /**
     * 工单超时失效时间
     */
    private LocalDateTime expireTime;

    /**
     * 审核完成时间
     */
    private LocalDateTime reviewTime;
}
