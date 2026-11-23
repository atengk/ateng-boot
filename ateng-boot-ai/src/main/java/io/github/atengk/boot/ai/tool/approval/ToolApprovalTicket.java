package io.github.atengk.boot.ai.tool.approval;

import io.github.atengk.boot.ai.annotation.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 高危工具调用审批工单快照实体模型
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolApprovalTicket implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工单全局唯一业务流水标识 (UUID)
     */
    private String ticketId;

    /**
     * 工具对外暴露的方法名称
     */
    private String toolName;

    /**
     * 目标类中的物理方法名称
     */
    private String methodName;

    /**
     * 目标 Bean 的全限定类名
     */
    private String targetClassName;

    /**
     * 目标 Bean 在 Spring 容器中的名称（若由 Spring 受管）
     */
    private String targetBeanName;

    /**
     * 触发调用的智能体 Bean 名称
     */
    private String agentBeanName;

    /**
     * 触发调用的智能体接口全限定类名
     */
    private String agentInterfaceName;

    /**
     * 触发调用的智能体会话记忆隔离标识 (sessionId / memoryId)
     */
    private String memoryId;

    /**
     * LangChain4j 工具执行唯一请求标识 (ToolExecutionRequest ID)
     */
    private String toolExecutionId;

    /**
     * 工具方法调用的完整反序列化入参快照 JSON
     */
    private String argumentsJson;

    /**
     * 工具操作风险等级
     */
    private RiskLevel riskLevel;

    /**
     * 业务审批分类标识
     */
    private String approvalType;

    /**
     * 敏感操作业务说明
     */
    private String description;

    /**
     * 触发调用的调用方用户标识 (从 UserContextHolder 提取)
     */
    private String callerUserId;

    /**
     * 触发调用的多租户标识 (从 TenantContextHolder 提取)
     */
    private String tenantId;

    /**
     * 全链路分布式追踪链路 ID (MDC traceId)
     */
    private String traceId;

    /**
     * 当前工单审批状态
     */
    private ToolApprovalStatus status;

    /**
     * 工单创建时间
     */
    private LocalDateTime createTime;

    /**
     * 工单超时失效绝对时间
     */
    private LocalDateTime expireTime;

    /**
     * 审核人填写的审批/拒绝批注意见
     */
    private String reviewComment;

    /**
     * 审核执行时间
     */
    private LocalDateTime reviewTime;

    /**
     * 审核通过后反射重放执行的返回值 JSON
     */
    private String executionResultJson;

    /**
     * 执行失败或异常错误描述
     */
    private String errorMessage;
}
