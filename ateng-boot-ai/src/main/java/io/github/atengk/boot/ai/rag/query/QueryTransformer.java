package io.github.atengk.boot.ai.rag.query;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.rag.query.Query;

import java.util.List;

/**
 * 检索查询改写与意图消解 SPI 顶层接口
 * <p>
 * 负责结合多轮历史对话上下文，将包含指代代词（如“它”、“其”、“那第二点”）或省略主语的用户问句，
 * 改写为具备完整独立语义的检索 Query，提升多轮问答场景下的知识库向量召回命中率。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public interface QueryTransformer {

    /**
     * 将用户当前 Query 结合历史多轮对话上下文改写为独立语义 Query
     *
     * @param query       用户当前原始查询对象
     * @param chatHistory 历史对话消息列表
     * @return 改写后的独立查询对象，若无需改写或无历史则返回原 Query
     */
    Query transform(Query query, List<ChatMessage> chatHistory);
}
