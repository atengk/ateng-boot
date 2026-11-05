package io.github.atengk.boot.ai.rag.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;

/**
 * 融合两阶段精排的复合知识库检索器
 * <p>
 * 封装底层向量检索器 {@link ContentRetriever} 执行向量初筛粗排召回 (Top-N)，
 * 随后委托 {@link Reranker} 进行 Cross-Encoder 语义精排 (Top-K)；
 * 具备生产级弹性降级机制：当重排模型超时或不可用时，自动降级回退至原始向量相似度排序，保障业务平滑不中断。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class RerankingContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(RerankingContentRetriever.class);

    private final ContentRetriever delegateRetriever;
    private final Reranker reranker;
    private final int topK;
    private final Double minScore;

    public RerankingContentRetriever(ContentRetriever delegateRetriever, Reranker reranker, int topK) {
        this(delegateRetriever, reranker, topK, null);
    }

    public RerankingContentRetriever(ContentRetriever delegateRetriever, Reranker reranker, int topK, Double minScore) {
        Assert.notNull(delegateRetriever, "delegateRetriever 不能为空");
        Assert.notNull(reranker, "reranker 不能为空");
        Assert.isTrue(topK > 0, "topK 必须大于 0");

        this.delegateRetriever = delegateRetriever;
        this.reranker = reranker;
        this.topK = topK;
        this.minScore = minScore;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return Collections.emptyList();
        }

        // 1. 向量粗排召回 (Vector Recall)
        List<Content> initialContents = delegateRetriever.retrieve(query);
        if (initialContents == null || initialContents.isEmpty()) {
            return Collections.emptyList();
        }

        if (initialContents.size() == 1) {
            return initialContents;
        }

        List<TextSegment> candidateSegments = initialContents.stream()
                .map(Content::textSegment)
                .toList();

        // 2. Cross-Encoder 交叉重排精排
        try {
            List<RerankResult> rerankResults = reranker.rerank(query.text(), candidateSegments, topK);
            if (rerankResults == null || rerankResults.isEmpty()) {
                log.warn("[AI-RAG-RERANK] 重排模型返回空结果，自动平滑降级采用原始向量召回前 {} 条结果", topK);
                return initialContents.subList(0, Math.min(topK, initialContents.size()));
            }

            return rerankResults.stream()
                    .filter(r -> minScore == null || (r.score() != null && r.score() >= minScore))
                    .map(r -> Content.from(r.segment()))
                    .toList();
        } catch (Exception e) {
            // 生产级弹性降级防线：重排外部服务不可用时，降级采用初筛前 topK 条结果，杜绝整条链路抛错中断
            log.warn("[AI-RAG-RERANK] 重排计算发生异常或超时 ({})，自动平滑降级为原始向量相似度排序", e.getMessage());
            return initialContents.subList(0, Math.min(topK, initialContents.size()));
        }
    }

    public ContentRetriever getDelegateRetriever() {
        return delegateRetriever;
    }

    public Reranker getReranker() {
        return reranker;
    }

    public int getTopK() {
        return topK;
    }

    public Double getMinScore() {
        return minScore;
    }
}
