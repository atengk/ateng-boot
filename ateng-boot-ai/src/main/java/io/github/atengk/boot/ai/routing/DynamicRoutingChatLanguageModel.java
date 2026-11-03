package io.github.atengk.boot.ai.routing;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.context.AgentCallContext;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.sanitizer.JsonPayloadSanitizer;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 动态路由与多模型切源聊天模型代理
 * 贯通运行时模型路由优先级：AiContextHolder 线程级切源 > @AiAgent(model) 接口静态配置 > 默认主配置模型
 * 同时内置提示词复合入参替换与大模型结构化输出 Markdown 脱壳去噪流水线。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class DynamicRoutingChatLanguageModel implements ChatLanguageModel {

    private final AiModelRegistry registry;
    private final String configuredModelName;

    public DynamicRoutingChatLanguageModel(AiModelRegistry registry, String configuredModelName) {
        this.registry = Objects.requireNonNull(registry, "AiModelRegistry 不能为空");
        this.configuredModelName = StringUtils.hasText(configuredModelName) ? configuredModelName.trim() : "";
    }

    /**
     * 动态推导当前应生效的底层大模型实例
     * 优先级：AiContextHolder (线程级) > configuredModelName (@AiAgent 静态配置) > 默认模型
     *
     * @return 生效的 ChatLanguageModel
     */
    public ChatLanguageModel resolveModel() {
        String threadModel = AiContextHolder.getModelName();
        if (StringUtils.hasText(threadModel)) {
            return registry.getChatModel(threadModel);
        }
        if (StringUtils.hasText(configuredModelName)) {
            return registry.getChatModel(configuredModelName);
        }
        return registry.getChatModel(AiModelRegistry.DEFAULT_MODEL_NAME);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        List<ChatMessage> preprocessed = preprocessMessages(messages);
        Response<AiMessage> response = resolveModel().generate(preprocessed);
        return postprocessResponse(response);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        List<ChatMessage> preprocessed = preprocessMessages(messages);
        Response<AiMessage> response = resolveModel().generate(preprocessed, toolSpecifications);
        return postprocessResponse(response);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        List<ChatMessage> preprocessed = preprocessMessages(messages);
        Response<AiMessage> response = resolveModel().generate(preprocessed, toolSpecification);
        return postprocessResponse(response);
    }

    /**
     * 前置处理消息：针对包含复合对象入参的 UserMessage，自动应用 JSON 格式化替换
     */
    private List<ChatMessage> preprocessMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>(messages.size());
        boolean modified = false;

        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                String original = um.singleText();
                String replaced = AgentCallContext.applyReplacements(original);
                if (replaced != null && !replaced.equals(original)) {
                    result.add(UserMessage.from(replaced));
                    modified = true;
                    continue;
                }
            } else if (msg instanceof SystemMessage sm) {
                String original = sm.text();
                String replaced = AgentCallContext.applyReplacements(original);
                if (replaced != null && !replaced.equals(original)) {
                    result.add(SystemMessage.from(replaced));
                    modified = true;
                    continue;
                }
            }
            result.add(msg);
        }

        return modified ? result : messages;
    }

    /**
     * 后置处理响应：剥除返回内容中的 Markdown 代码块语法糖（```json ... ```）
     */
    private Response<AiMessage> postprocessResponse(Response<AiMessage> response) {
        if (response == null || response.content() == null) {
            return response;
        }
        AiMessage content = response.content();
        String text = content.text();
        if (StringUtils.hasText(text) && text.contains("```")) {
            String sanitized = JsonPayloadSanitizer.sanitize(text);
            if (StringUtils.hasText(sanitized) && !sanitized.equals(text)) {
                AiMessage newContent = content.hasToolExecutionRequests()
                        ? AiMessage.from(sanitized, content.toolExecutionRequests())
                        : AiMessage.from(sanitized);
                return Response.from(newContent, response.tokenUsage(), response.finishReason());
            }
        }
        return response;
    }
}
