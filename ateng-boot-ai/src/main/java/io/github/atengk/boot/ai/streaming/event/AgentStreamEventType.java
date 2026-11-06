package io.github.atengk.boot.ai.streaming.event;

/**
 * 智能体过程流式事件类型枚举
 *
 * @author Ateng
 * @since 2026-11-06
 */
public enum AgentStreamEventType {

    /**
     * 思考链推理事件 (DeepSeek-R1 等模型的思考过程)
     */
    REASONING("reasoning"),

    /**
     * 思考过程事件别名
     */
    AGENT_THOUGHT("agent_thought"),

    /**
     * 工具调用触发事件 (包含工具名称、调用标识与参数)
     */
    TOOL_CALL("tool_call"),

    /**
     * 工具执行返回结果事件 (包含工具名称、调用标识与输出快照)
     */
    TOOL_RESULT("tool_result"),

    /**
     * 知识库 RAG 切片召回候选内容事件
     */
    RAG_RETRIEVED("rag_retrieved"),

    /**
     * 回答正文打字机推流事件
     */
    TEXT("text"),

    /**
     * 可信溯源引用数据包事件
     */
    CITATION("citation"),

    /**
     * 流式推流正常完成结束标志事件
     */
    DONE("done"),

    /**
     * 流式推流异常终止事件
     */
    ERROR("error");

    private final String eventName;

    AgentStreamEventType(String eventName) {
        this.eventName = eventName;
    }

    /**
     * 获取对应的 SSE 事件名
     *
     * @return SSE event 字符串
     */
    public String getEventName() {
        return eventName;
    }
}
