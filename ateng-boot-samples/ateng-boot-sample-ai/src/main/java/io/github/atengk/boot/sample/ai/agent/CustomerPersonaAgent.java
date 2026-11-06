package io.github.atengk.boot.sample.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.UserName;
import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.ai.annotation.UserId;

/**
 * 支持声明式长效情景记忆与事实双向沉淀的客户画像智能体
 *
 * @author Ateng
 * @since 2026-11-06
 */
@AiAgent(episodicMemory = true, extractMemory = true)
public interface CustomerPersonaAgent {

    @SystemMessage("你是一个企业级贴心客户助理，请根据用户的背景与偏好提供个性化服务")
    String chatWithUser(@UserId @UserName String userId, @UserMessage String message);

    @SystemMessage("你是一个企业级贴心客户助理，请根据用户的背景与偏好提供个性化服务")
    String chatWithSession(@MemoryId String sessionId, @UserMessage String message);

    @SystemMessage("你是一个企业级贴心客户助理，请根据用户的背景与偏好提供个性化服务")
    String chat(@UserMessage String message);
}
