package io.github.atengk.boot.ai.exception;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 大模型与智能体底座业务状态码枚举 (61001~61020)
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Getter
@AllArgsConstructor
public enum AiResultCode implements IResultCode {

    /**
     * 大模型调用失败
     */
    AI_CALL_FAILED(61001, "大模型调用失败"),

    /**
     * 未找到指定的大模型实例
     */
    AI_MODEL_NOT_FOUND(61002, "未找到指定的大模型实例"),

    /**
     * 大模型调用触发频控限制 (429)
     */
    AI_RATE_LIMIT_EXCEEDED(61003, "大模型调用过于频繁，触发限流保护 (HTTP 429)"),

    /**
     * 大模型服务鉴权失败
     */
    AI_AUTH_FAILED(61004, "大模型服务鉴权失败，请检查 API Key 配置"),

    /**
     * 大模型网络通信超时
     */
    AI_TIMEOUT(61005, "大模型网络连接或读取响应超时"),

    /**
     * 大模型载荷或入参非法
     */
    AI_INVALID_PAYLOAD(61006, "大模型请求参数或载荷格式不合法"),

    /**
     * 大模型基础配置错误
     */
    AI_CONFIG_ERROR(61007, "大模型基础配置错误或缺少关键参数"),

    /**
     * 大模型响应内容反序列化或格式校验失败
     */
    AI_RESPONSE_PARSE_ERROR(61008, "大模型响应内容反序列化或格式校验失败"),

    /**
     * 智能体代理配置或依赖装配错误
     */
    AI_AGENT_CONFIG_ERROR(61009, "智能体代理配置或依赖装配错误"),

    /**
     * 智能体会话记忆存储读写失败
     */
    AI_MEMORY_STORE_ERROR(61010, "智能体会话记忆存储读写失败"),

    /**
     * 未找到指定名称的认知状态图工作流
     */
    AI_GRAPH_WORKFLOW_NOT_FOUND(61011, "未找到指定名称的认知状态图工作流"),

    /**
     * 认知图检查点快照不存在
     */
    AI_GRAPH_SNAPSHOT_NOT_FOUND(61012, "认知图检查点快照不存在"),

    /**
     * 认知状态图工作流非挂起状态或已在执行，严禁重复恢复
     */
    AI_GRAPH_ALREADY_RESUMED(61013, "认知状态图工作流非挂起状态或已在执行，严禁重复恢复"),

    /**
     * 认知图并发审批竞争中，请勿重复操作
     */
    AI_GRAPH_LOCK_CONFLICT(61014, "认知图并发审批竞争中，请勿重复操作"),

    /**
     * 认知状态图工作流已被撤销，无法恢复执行
     */
    AI_GRAPH_ALREADY_CANCELLED(61015, "认知状态图工作流已被撤销，无法恢复执行"),

    /**
     * 认知状态图工作流执行或状态持久化失败
     */
    AI_GRAPH_EXECUTION_ERROR(61016, "认知状态图工作流执行或状态持久化失败"),

    /**
     * 大模型调用配额用尽或超出风控上限
     */
    AI_QUOTA_EXCEEDED(61017, "大模型调用配额用尽或超出风控上限"),

    /**
     * 检测到内容安全合规风险或越狱注入攻击
     */
    AI_CONTENT_RISK_DETECTED(61018, "检测到内容安全合规风险或越狱注入攻击"),

    /**
     * 远程 MCP 服务通信或工具执行失败
     */
    AI_MCP_CALL_FAILED(61019, "远程 MCP 服务通信或工具执行失败"),

    /**
     * 文档内容提取或解析失败
     */
    AI_PARSE_ERROR(61020, "文档内容提取或解析失败"),

    /**
     * 高危工具操作需要人工审批拦截
     */
    AI_TOOL_APPROVAL_REQUIRED(61021, "高危工具操作需要人工审批"),

    /**
     * 未找到指定的工具审批工单
     */
    AI_TOOL_APPROVAL_NOT_FOUND(61022, "未找到指定的工具审批工单"),

    /**
     * 工具审批工单处于非待审批状态
     */
    AI_TOOL_APPROVAL_INVALID_STATUS(61023, "工具审批工单处于非待审批状态"),

    /**
     * 工具审批工单已超时失效
     */
    AI_TOOL_APPROVAL_EXPIRED(61024, "工具审批工单已超时失效"),

    /**
     * 工具审批工单已被拒绝执行
     */
    AI_TOOL_APPROVAL_REJECTED(61025, "工具审批工单已被拒绝执行"),

    /**
     * 主管多智能体协同工作流达到最大轮次看门狗上限
     */
    AI_SUPERVISOR_WATCHDOG_TRIPPED(61026, "主管多智能体协同工作流达到最大轮次看门狗上限"),

    /**
     * 未找到指定名称的专工 Worker
     */
    AI_SUPERVISOR_WORKER_NOT_FOUND(61027, "未找到指定名称的专工 Worker");

    private final Integer code;
    private final String msg;
}
