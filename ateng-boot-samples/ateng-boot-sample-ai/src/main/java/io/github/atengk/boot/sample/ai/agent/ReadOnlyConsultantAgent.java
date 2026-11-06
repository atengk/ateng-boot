package io.github.atengk.boot.sample.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.UserName;
import io.github.atengk.boot.ai.annotation.AiAgent;
import io.github.atengk.boot.ai.annotation.UserId;

/**
 * 只读长效情景记忆咨询智能体（仅感知注入用户背景偏好，不触发异步提取）
 *
 * @author Ateng
 * @since 2026-11-06
 */
@AiAgent(episodicMemory = true, extractMemory = false)
public interface ReadOnlyConsultantAgent {

    @SystemMessage("你是一个专业投资顾问，请参考用户背景偏好提供建议")
    String consult(@UserId @UserName String userId, @UserMessage String message);
}
