package io.github.atengk.boot.ai.rag.fusion;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 稠密向量与稀疏关键词混合知识库检索器 (HybridContentRetriever)
 * <p>
 * 遵循现代 RAG 混合检索架构，将基于语义向量相似度的稠密召回 (Dense Recall) 与基于关键字词频匹配的稀疏召回 (Sparse Recall)
 * 进行多路并发检索，并依托 {@link ReciprocalRankFuser} 执行 RRF 倒数排名融合。
 * 具备生产级弹性降级能力：当某路检索出现瞬态异常时，自动平滑退避使用另一路召回结果，杜绝级联故障。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class HybridContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridContentRetriever.class);

    private final ContentRetriever denseRetriever;
    private final ContentRetriever sparseRetriever;
    private final int topK;
    private final int rrfK;
    private final double denseWeight;
    private final double sparseWeight;

    public HybridContentRetriever(ContentRetriever denseRetriever,
                                  ContentRetriever sparseRetriever,
                                  int topK) {
        this(denseRetriever, sparseRetriever, topK, ReciprocalRankFuser.DEFAULT_RRF_K, 1.0, 1.0);
    }

    public HybridContentRetriever(ContentRetriever denseRetriever,
                                  ContentRetriever sparseRetriever,
                                  int topK,
                                  int rrfK,
                                  double denseWeight,
                                  double sparseWeight) {
        Assert.notNull(denseRetriever, "denseRetriever 稠密向量检索器不能为空");
        Assert.notNull(sparseRetriever, "sparseRetriever 稀疏关键词检索器不能为空");
        Assert.isTrue(topK > 0, "topK 必须大于 0");
        this.denseRetriever = denseRetriever;
        this.sparseRetriever = sparseRetriever;
        this.topK = topK;
        this.rrfK = rrfK > 0 ? rrfK : ReciprocalRankFuser.DEFAULT_RRF_K;
        this.denseWeight = denseWeight > 0 ? denseWeight : 1.0;
        this.sparseWeight = sparseWeight > 0 ? sparseWeight : 1.0;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return Collections.emptyList();
        }

        List<Content> denseResults = Collections.emptyList();
        try {
            denseResults = denseRetriever.retrieve(query);
            if (denseResults == null) {
                denseResults = Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("[AI-RAG-HYBRID] 稠密向量检索召回异常 ({})，平滑降级", e.getMessage());
        }

        List<Content> sparseResults = Collections.emptyList();
        try {
            sparseResults = sparseRetriever.retrieve(query);
            if (sparseResults == null) {
                sparseResults = Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("[AI-RAG-HYBRID] 稀疏关键词检索召回异常 ({})，平滑降级", e.getMessage());
        }

        if (denseResults.isEmpty() && sparseResults.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Content>> candidateLists = new ArrayList<>();
        candidateLists.add(denseResults);
        candidateLists.add(sparseResults);

        List<Double> weights = List.of(denseWeight, sparseWeight);
        return ReciprocalRankFuser.fuse(candidateLists, weights, topK, rrfK);
    }

    public ContentRetriever getDenseRetriever() {
        return denseRetriever;
    }

    public ContentRetriever getSparseRetriever() {
        return sparseRetriever;
    }

    public int getTopK() {
        return topK;
    }

    public int getRrfK() {
        return rrfK;
    }

    public double getDenseWeight() {
        return denseWeight;
    }

    public double getSparseWeight() {
        return sparseWeight;
    }
}
