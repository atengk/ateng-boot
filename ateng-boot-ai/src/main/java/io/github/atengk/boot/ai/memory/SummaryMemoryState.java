package io.github.atengk.boot.ai.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 长会话记忆双层状态模型
 * <p>
 * 封装长期摘要（Summary）与近期详细历史消息（Messages），
 * 采用原生纯净 JSON 序列化，彻底杜绝 Jackson {@code @class} 多态反序列化漏洞与元数据污染。
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class SummaryMemoryState implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(SummaryMemoryState.class);

    private final String summary;
    private final List<ChatMessage> messages;

    /**
     * 创建默认空状态
     */
    public SummaryMemoryState() {
        this("", Collections.emptyList());
    }

    /**
     * 显式构造长会话双层记忆状态
     *
     * @param summary  长期事实摘要，允许为 null（内部自动标准化为空字符串）
     * @param messages 近期详细对话消息，允许为 null（内部自动标准化为不可变空列表）
     */
    public SummaryMemoryState(String summary, List<ChatMessage> messages) {
        this.summary = summary != null ? summary : "";
        if (messages == null || messages.isEmpty()) {
            this.messages = Collections.emptyList();
        } else {
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        }
    }

    /**
     * 获取长期事实摘要（保底返回非空字符串，绝不返回 null）
     *
     * @return 长期事实摘要
     */
    public String getSummary() {
        return summary;
    }

    /**
     * 获取近期详细历史消息列表（保底返回不可变非空列表，绝不返回 null）
     *
     * @return 历史消息列表只读副本
     */
    public List<ChatMessage> getMessages() {
        return messages;
    }

    /**
     * 判断当前是否已形成有效非空的长期摘要
     *
     * @return true 若长期摘要非空
     */
    public boolean hasSummary() {
        return StringUtils.hasText(summary);
    }

    /**
     * 将当前状态序列化为原生纯净 JSON 字符串，格式为 {@code {"summary": "...", "messages": [...]}}
     *
     * @return 纯净 JSON 字符串，绝不包含 {@code @class} 元数据
     */
    public String toJson() {
        try {
            ObjectNode root = (ObjectNode) JsonUtil.readTree("{}");
            root.put("summary", this.summary);
            if (this.messages != null && !this.messages.isEmpty()) {
                String messagesJson = ChatMessageSerializer.messagesToJson(this.messages);
                JsonNode messagesNode = JsonUtil.readTree(messagesJson);
                root.set("messages", messagesNode);
            } else {
                root.putArray("messages");
            }
            return root.toString();
        } catch (Exception e) {
            log.warn("[AI-SUMMARY-STATE] 序列化 SummaryMemoryState 失败: {}", e.getMessage(), e);
            return "{\"summary\":\"\",\"messages\":[]}";
        }
    }

    /**
     * 从原生纯净 JSON 字符串反序列化为 SummaryMemoryState
     *
     * @param json JSON 字符串，若为空或解析异常则保底返回空状态
     * @return SummaryMemoryState 实例，绝不返回 null
     */
    public static SummaryMemoryState fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return new SummaryMemoryState();
        }
        try {
            JsonNode root = JsonUtil.readTree(json);
            if (root == null || !root.isObject()) {
                return new SummaryMemoryState();
            }
            String summary = root.hasNonNull("summary") ? root.get("summary").asText() : "";
            List<ChatMessage> messages = Collections.emptyList();
            if (root.hasNonNull("messages")) {
                JsonNode messagesNode = root.get("messages");
                if (messagesNode.isArray() && !messagesNode.isEmpty()) {
                    messages = ChatMessageDeserializer.messagesFromJson(messagesNode.toString());
                }
            }
            return new SummaryMemoryState(summary, messages);
        } catch (Exception e) {
            log.warn("[AI-SUMMARY-STATE] 反序列化 SummaryMemoryState 失败，降级为空状态: {}", e.getMessage(), e);
            return new SummaryMemoryState();
        }
    }

    @Override
    public String toString() {
        return "SummaryMemoryState{" +
                "summaryLength=" + summary.length() +
                ", messageCount=" + messages.size() +
                '}';
    }
}
