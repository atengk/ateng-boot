package io.github.atengk.boot.ai.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.Tokenizer;
import io.github.atengk.boot.ai.observability.FallbackTokenEstimator;

import java.util.Collections;

/**
 * 基于中英文加权启发式算法的轻量保底分词器
 * 实现 LangChain4j Tokenizer SPI，零外部字典依赖，在离线环境或任意国产/开源模型下保底推算 Token 总量
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class HeuristicTokenizer implements Tokenizer {

    @Override
    public int estimateTokenCountInText(String text) {
        return FallbackTokenEstimator.estimateTokens(text);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        // 单条消息基本内容 Token 加上 OpenAI 协议固有 3-4 Token 角色标头封装开销
        return FallbackTokenEstimator.estimatePromptTokens(Collections.singletonList(message)) + 3;
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateTokenCountInMessage(message);
        }
        return total;
    }

    @Override
    public int estimateTokenCountInToolSpecifications(Iterable<ToolSpecification> toolSpecifications) {
        if (toolSpecifications == null) {
            return 0;
        }
        int total = 0;
        for (ToolSpecification spec : toolSpecifications) {
            if (spec != null) {
                total += FallbackTokenEstimator.estimateTokens(spec.name())
                        + FallbackTokenEstimator.estimateTokens(spec.description());
            }
        }
        return total;
    }

    @Override
    public int estimateTokenCountInToolExecutionRequests(Iterable<ToolExecutionRequest> toolExecutionRequests) {
        if (toolExecutionRequests == null) {
            return 0;
        }
        int total = 0;
        for (ToolExecutionRequest req : toolExecutionRequests) {
            if (req != null) {
                total += FallbackTokenEstimator.estimateTokens(req.name())
                        + FallbackTokenEstimator.estimateTokens(req.arguments());
            }
        }
        return total;
    }
}
