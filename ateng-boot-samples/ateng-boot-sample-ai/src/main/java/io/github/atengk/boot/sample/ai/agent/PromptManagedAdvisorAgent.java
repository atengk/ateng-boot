package io.github.atengk.boot.sample.ai.agent;

import dev.langchain4j.service.UserMessage;
import io.github.atengk.boot.ai.annotation.AiAgent;

/**
 * 绑定动态托管系统提示词（promptCode = "advisor-system"）的声明式智能体样例接口
 *
 * @author Ateng
 * @since 2026-11-22
 */
@AiAgent(promptCode = "advisor-system")
public interface PromptManagedAdvisorAgent {

    /**
     * 基于动态系统提示词回答用户咨询
     *
     * @param question 用户问题
     * @return 智能体回复正文
     */
    String advise(@UserMessage String question);
}
