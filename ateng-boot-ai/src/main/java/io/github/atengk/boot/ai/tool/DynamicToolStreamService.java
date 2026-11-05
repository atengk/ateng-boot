package io.github.atengk.boot.ai.tool;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 编程式动态工具流式调用 AI 服务接口
 * <p>
 * 供 {@link io.github.atengk.boot.ai.template.AiTemplate#streamWithTools} 内部构建局部受管 LangChain4j AiServices 代理，
 * 返回流式 {@link TokenStream} 并直接桥接至 Spring MVC {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public interface DynamicToolStreamService {

    /**
     * 发送用户提示词并触发工具调用生成流式响应管道
     *
     * @param prompt 用户提示词
     * @return LangChain4j 流式 TokenStream 管道
     */
    TokenStream stream(@UserMessage String prompt);
}
