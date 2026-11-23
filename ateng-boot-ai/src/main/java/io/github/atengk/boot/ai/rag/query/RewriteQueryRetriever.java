package io.github.atengk.boot.ai.rag.query;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;

/**
 * 具备多轮对话意图消解与查询改写的知识库检索器
 * <p>
 * 在发起知识库检索前，自动提取 {@link Query#metadata()} 中的多轮对话历史 {@code chatMemory()}，
 * 委托 {@link QueryTransformer} 将代词指代消解改写为独立语义 Query，随后再交由下游检索器进行高精召回。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class RewriteQueryRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(RewriteQueryRetriever.class);

    private final ContentRetriever delegateRetriever;
    private final QueryTransformer queryTransformer;

    public RewriteQueryRetriever(ContentRetriever delegateRetriever, QueryTransformer queryTransformer) {
        Assert.notNull(delegateRetriever, "delegateRetriever 不能为空");
        Assert.notNull(queryTransformer, "queryTransformer 不能为空");
        this.delegateRetriever = delegateRetriever;
        this.queryTransformer = queryTransformer;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return Collections.emptyList();
        }

        List<ChatMessage> chatHistory = Collections.emptyList();
        if (query.metadata() != null && query.metadata().chatMemory() != null) {
            chatHistory = query.metadata().chatMemory();
        }

        Query effectiveQuery = query;
        if (!chatHistory.isEmpty()) {
            try {
                effectiveQuery = queryTransformer.transform(query, chatHistory);
            } catch (Exception e) {
                log.warn("[AI-RAG-REWRITE] 查询改写执行异常 ({})，自动平滑降级使用原查询", e.getMessage());
                effectiveQuery = query;
            }
        }

        return delegateRetriever.retrieve(effectiveQuery);
    }

    public ContentRetriever getDelegateRetriever() {
        return delegateRetriever;
    }

    public QueryTransformer getQueryTransformer() {
        return queryTransformer;
    }
}
