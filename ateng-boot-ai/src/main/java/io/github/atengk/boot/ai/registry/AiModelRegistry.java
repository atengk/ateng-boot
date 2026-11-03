package io.github.atengk.boot.ai.registry;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 大模型与流式引擎多命名统一注册中心
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class AiModelRegistry {

    /**
     * 默认模型路由键
     */
    public static final String DEFAULT_MODEL_NAME = "default";

    private final Map<String, ChatLanguageModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatLanguageModel> streamingChatModels = new ConcurrentHashMap<>();

    /**
     * 注册同步聊天模型与流式聊天模型
     *
     * @param name               模型标识名称
     * @param chatModel          同步聊天模型
     * @param streamingChatModel 流式聊天模型
     */
    public void register(String name, ChatLanguageModel chatModel, StreamingChatLanguageModel streamingChatModel) {
        if (!StringUtils.hasText(name)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "注册模型名称不能为空");
        }
        String cleanName = name.trim();
        if (chatModel != null) {
            chatModels.put(cleanName, chatModel);
        }
        if (streamingChatModel != null) {
            streamingChatModels.put(cleanName, streamingChatModel);
        }
    }

    /**
     * 单独注册同步聊天模型
     *
     * @param name      模型标识名称
     * @param chatModel 同步聊天模型
     */
    public void registerChatModel(String name, ChatLanguageModel chatModel) {
        if (!StringUtils.hasText(name)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "注册模型名称不能为空");
        }
        if (chatModel != null) {
            chatModels.put(name.trim(), chatModel);
        }
    }

    /**
     * 单独注册流式聊天模型
     *
     * @param name               模型标识名称
     * @param streamingChatModel 流式聊天模型
     */
    public void registerStreamingChatModel(String name, StreamingChatLanguageModel streamingChatModel) {
        if (!StringUtils.hasText(name)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "注册模型名称不能为空");
        }
        if (streamingChatModel != null) {
            streamingChatModels.put(name.trim(), streamingChatModel);
        }
    }

    /**
     * 注册主默认模型及可选别名
     *
     * @param chatModel          同步聊天模型
     * @param streamingChatModel 流式聊天模型
     * @param alias              模型自定义别名 (如 gpt-4o 或 deepseek-chat)
     */
    public void registerDefault(ChatLanguageModel chatModel, StreamingChatLanguageModel streamingChatModel, String alias) {
        register(DEFAULT_MODEL_NAME, chatModel, streamingChatModel);
        if (StringUtils.hasText(alias) && !DEFAULT_MODEL_NAME.equalsIgnoreCase(alias.trim())) {
            register(alias.trim(), chatModel, streamingChatModel);
        }
    }

    /**
     * 获取当前上下文活跃的同步聊天模型；若上下文未指定则返回默认模型
     *
     * @return ChatLanguageModel 实例
     */
    public ChatLanguageModel getChatModel() {
        String current = AiContextHolder.getModelName();
        if (StringUtils.hasText(current)) {
            return getChatModel(current);
        }
        return getChatModel(DEFAULT_MODEL_NAME);
    }

    /**
     * 根据名称查找同步聊天模型
     *
     * @param name 模型标识名称
     * @return ChatLanguageModel 实例
     * @throws AiException 若未找到对应模型实例
     */
    public ChatLanguageModel getChatModel(String name) {
        if (!StringUtils.hasText(name)) {
            throw new AiException(AiResultCode.AI_MODEL_NOT_FOUND, "查找模型名称不能为空");
        }
        ChatLanguageModel model = chatModels.get(name.trim());
        if (model == null) {
            throw new AiException(AiResultCode.AI_MODEL_NOT_FOUND, "未找到名称为 [" + name + "] 的大模型实例，当前已注册: " + chatModels.keySet());
        }
        return model;
    }

    /**
     * 获取当前上下文活跃的流式聊天模型；若上下文未指定则返回默认流式模型
     *
     * @return StreamingChatLanguageModel 实例
     */
    public StreamingChatLanguageModel getStreamingChatModel() {
        String current = AiContextHolder.getModelName();
        if (StringUtils.hasText(current)) {
            return getStreamingChatModel(current);
        }
        return getStreamingChatModel(DEFAULT_MODEL_NAME);
    }

    /**
     * 根据名称查找流式聊天模型
     *
     * @param name 模型标识名称
     * @return StreamingChatLanguageModel 实例
     * @throws AiException 若未找到对应流式模型实例
     */
    public StreamingChatLanguageModel getStreamingChatModel(String name) {
        if (!StringUtils.hasText(name)) {
            throw new AiException(AiResultCode.AI_MODEL_NOT_FOUND, "查找模型名称不能为空");
        }
        StreamingChatLanguageModel model = streamingChatModels.get(name.trim());
        if (model == null) {
            throw new AiException(AiResultCode.AI_MODEL_NOT_FOUND, "未找到名称为 [" + name + "] 的流式大模型实例，当前已注册: " + streamingChatModels.keySet());
        }
        return model;
    }

    /**
     * 判断是否存在指定名称的同步聊天模型
     *
     * @param name 模型名称
     * @return 存在返回 true，否则返回 false
     */
    public boolean hasChatModel(String name) {
        return StringUtils.hasText(name) && chatModels.containsKey(name.trim());
    }

    /**
     * 判断是否存在指定名称的流式聊天模型
     *
     * @param name 模型名称
     * @return 存在返回 true，否则返回 false
     */
    public boolean hasStreamingChatModel(String name) {
        return StringUtils.hasText(name) && streamingChatModels.containsKey(name.trim());
    }

    /**
     * 判断注册表中是否包含任何已就绪的模型
     *
     * @return 若存在模型返回 true，否则返回 false
     */
    public boolean hasModels() {
        return !chatModels.isEmpty() || !streamingChatModels.isEmpty();
    }

    /**
     * 获取所有已注册的模型名称集合
     *
     * @return 只读模型名称集合
     */
    public Set<String> getModelNames() {
        Set<String> names = new LinkedHashSet<>(chatModels.keySet());
        names.addAll(streamingChatModels.keySet());
        return Collections.unmodifiableSet(names);
    }

    /**
     * 移除指定名称的模型
     *
     * @param name 模型名称
     */
    public void remove(String name) {
        if (StringUtils.hasText(name)) {
            chatModels.remove(name.trim());
            streamingChatModels.remove(name.trim());
        }
    }

    /**
     * 清空注册表中的所有模型
     */
    public void clear() {
        chatModels.clear();
        streamingChatModels.clear();
    }
}
