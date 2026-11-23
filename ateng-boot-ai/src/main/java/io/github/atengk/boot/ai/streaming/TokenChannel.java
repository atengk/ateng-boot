package io.github.atengk.boot.ai.streaming;

/**
 * 流式输出通道枚举
 *
 * @author Ateng
 * @since 2026-11-03
 */
public enum TokenChannel {

    /**
     * 思考链推理通道 (DeepSeek-R1 思考过程)
     */
    REASONING("reasoning"),

    /**
     * 回答正文通道
     */
    TEXT("text");

    private final String eventName;

    TokenChannel(String eventName) {
        this.eventName = eventName;
    }

    /**
     * 获取对应的 SSE 事件名称
     *
     * @return 事件名称字符串
     */
    public String getEventName() {
        return eventName;
    }
}
