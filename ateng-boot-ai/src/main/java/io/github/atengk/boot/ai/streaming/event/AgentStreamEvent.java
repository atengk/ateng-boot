package io.github.atengk.boot.ai.streaming.event;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 智能体过程流式事件强类型契约模型
 *
 * @param eventType 事件类型名称 (对齐 SSE event 标头)
 * @param data      事件数据载荷对象
 * @param timestamp 事件生成绝对时间戳
 * @author Ateng
 * @since 2026-11-06
 */
public record AgentStreamEvent(
        String eventType,
        Object data,
        LocalDateTime timestamp
) implements Serializable {

    public AgentStreamEvent {
        Objects.requireNonNull(eventType, "eventType 不能为空");
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }

    /**
     * 构建自定义通用流式事件
     *
     * @param eventType 事件类型
     * @param data      事件数据载荷
     * @return 智能体流式事件实例
     */
    public static AgentStreamEvent of(String eventType, Object data) {
        return new AgentStreamEvent(eventType, data, LocalDateTime.now());
    }

    /**
     * 构建思考链推理 Token 事件
     *
     * @param token 思考片段
     * @return 流式事件
     */
    public static AgentStreamEvent reasoning(String token) {
        return of(AgentStreamEventType.REASONING.getEventName(), token);
    }

    /**
     * 构建思考链推理 Token 事件 (agent_thought 通道)
     *
     * @param token 思考片段
     * @return 流式事件
     */
    public static AgentStreamEvent thought(String token) {
        return of(AgentStreamEventType.AGENT_THOUGHT.getEventName(), token);
    }

    /**
     * 构建回答正文打字机 Token 事件
     *
     * @param token 正文片段
     * @return 流式事件
     */
    public static AgentStreamEvent text(String token) {
        return of(AgentStreamEventType.TEXT.getEventName(), token);
    }

    /**
     * 构建工具调用触发事件
     *
     * @param id        调用标识
     * @param name      工具名称
     * @param arguments 调用入参 JSON
     * @return 流式事件
     */
    public static AgentStreamEvent toolCall(String id, String name, String arguments) {
        return of(AgentStreamEventType.TOOL_CALL.getEventName(), new ToolCallPayload(id, name, arguments));
    }

    /**
     * 构建工具执行结果返回事件
     *
     * @param id     调用标识
     * @param name   工具名称
     * @param result 执行返回结果
     * @return 流式事件
     */
    public static AgentStreamEvent toolResult(String id, String name, String result) {
        return of(AgentStreamEventType.TOOL_RESULT.getEventName(), new ToolResultPayload(id, name, result));
    }

    /**
     * 构建 RAG 知识检索切片候选事件
     *
     * @param contents 召回切片数据
     * @return 流式事件
     */
    public static AgentStreamEvent ragRetrieved(Object contents) {
        return of(AgentStreamEventType.RAG_RETRIEVED.getEventName(), contents);
    }

    /**
     * 构建引用溯源数据包事件
     *
     * @param citations 引用列表
     * @return 流式事件
     */
    public static AgentStreamEvent citation(Object citations) {
        return of(AgentStreamEventType.CITATION.getEventName(), citations);
    }

    /**
     * 构建正常结束事件
     *
     * @return 流式事件
     */
    public static AgentStreamEvent done() {
        return of(AgentStreamEventType.DONE.getEventName(), "[DONE]");
    }

    /**
     * 构建异常终止事件
     *
     * @param message 错误信息
     * @return 流式事件
     */
    public static AgentStreamEvent error(String message) {
        return of(AgentStreamEventType.ERROR.getEventName(), message);
    }

    /**
     * 工具调用参数载荷模型
     *
     * @param id        工具调用唯一标识
     * @param name      调用的工具方法名称
     * @param arguments 调用入参 JSON 字符串
     */
    public record ToolCallPayload(String id, String name, String arguments) implements Serializable {}

    /**
     * 工具执行结果载荷模型
     *
     * @param id     工具调用唯一标识
     * @param name   调用的工具方法名称
     * @param result 执行返回结果字符串
     */
    public record ToolResultPayload(String id, String name, String result) implements Serializable {}
}
