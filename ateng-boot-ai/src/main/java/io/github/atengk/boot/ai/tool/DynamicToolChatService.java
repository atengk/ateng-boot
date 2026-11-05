package io.github.atengk.boot.ai.tool;

import dev.langchain4j.service.UserMessage;

/**
 * 编程式动态工具同步调用 AI 服务接口
 * <p>
 * 供 {@link io.github.atengk.boot.ai.template.AiTemplate#chatWithTools} 内部构建局部受管 LangChain4j AiServices 代理。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public interface DynamicToolChatService {

    /**
     * 发送用户提示词并触发工具调用生成大模型回复正文
     *
     * @param prompt 用户提示词
     * @return 大模型响应文本
     */
    String chat(@UserMessage String prompt);
}
