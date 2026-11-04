package io.github.atengk.boot.ai.routing;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import io.github.atengk.boot.ai.context.AgentCallContext;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.tenant.AiTenantModelResolver;
import io.github.atengk.boot.core.context.TenantContextHolder;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态模型路由与入参预处理通用辅助类
 * 消除 DynamicRoutingChatLanguageModel 与 DynamicRoutingStreamingChatLanguageModel 间的重复逻辑
 *
 * @author Ateng
 * @since 2026-11-04
 */
final class DynamicRoutingSupport {

    private DynamicRoutingSupport() {
    }

    /**
     * 动态推导当前应生效的目标大模型名称
     * 优先级：AiContextHolder (线程级) > AiTenantModelResolver (多租户动态切源) > configuredModelName (@AiAgent 静态配置) > 默认模型
     *
     * @param configuredModelName 配置的模型名称
     * @param tenantModelResolver 租户模型解析器
     * @return 生效的目标模型名称
     */
    static String resolveModelName(String configuredModelName, AiTenantModelResolver tenantModelResolver) {
        String threadModel = AiContextHolder.getModelName();
        if (StringUtils.hasText(threadModel)) {
            return threadModel.trim();
        }
        String target = configuredModelName;
        if (tenantModelResolver != null && !TenantContextHolder.isIgnoreTenant()) {
            String tenantId = TenantContextHolder.getTenantId();
            if (StringUtils.hasText(tenantId)) {
                target = tenantModelResolver.resolveModel(tenantId.trim(), target);
            }
        }
        if (StringUtils.hasText(target)) {
            return target;
        }
        return AiModelRegistry.DEFAULT_MODEL_NAME;
    }

    /**
     * 前置处理消息：针对包含复合对象入参的 UserMessage / SystemMessage，自动应用 JSON 格式化替换
     *
     * @param messages 原始消息列表
     * @return 预处理后的消息列表
     */
    static List<ChatMessage> preprocessMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>(messages.size());
        boolean modified = false;

        for (ChatMessage msg : messages) {
            ChatMessage processed = processSingleMessage(msg);
            if (processed != msg) {
                result.add(processed);
                modified = true;
            } else {
                result.add(msg);
            }
        }

        return modified ? result : messages;
    }

    private static ChatMessage processSingleMessage(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            if (um.contents() != null && !um.contents().isEmpty()) {
                boolean anyReplaced = false;
                List<Content> newContents = new ArrayList<>(um.contents().size());
                for (Content c : um.contents()) {
                    if (c instanceof TextContent tc) {
                        String original = tc.text();
                        String replaced = AgentCallContext.applyReplacements(original);
                        if (replaced != null && !replaced.equals(original)) {
                            newContents.add(TextContent.from(replaced));
                            anyReplaced = true;
                            continue;
                        }
                    }
                    newContents.add(c);
                }
                if (anyReplaced) {
                    return UserMessage.from(newContents);
                }
            } else if (um.hasSingleText()) {
                String original = um.singleText();
                String replaced = AgentCallContext.applyReplacements(original);
                if (replaced != null && !replaced.equals(original)) {
                    return UserMessage.from(replaced);
                }
            }
        } else if (msg instanceof SystemMessage sm) {
            String original = sm.text();
            String replaced = AgentCallContext.applyReplacements(original);
            if (replaced != null && !replaced.equals(original)) {
                return SystemMessage.from(replaced);
            }
        }
        return msg;
    }
}
