package io.github.atengk.boot.ai.rag.filter;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;

/**
 * 支持动态元数据权限下推与租户隔离的知识库检索器 (DynamicFilterContentRetriever)
 * <p>
 * 在每次执行检索时，自动结合当前线程绑定的动态元数据过滤条件与 SaaS 租户上下文，
 * 将精准的 {@link Filter} 表达式直接下推至底层向量数据库 (EmbeddingStore)，杜绝跨租户与越权脏读。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class DynamicFilterContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(DynamicFilterContentRetriever.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final int maxResults;
    private final Double minScore;
    private final Filter baseFilter;

    public DynamicFilterContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                         EmbeddingModel embeddingModel,
                                         int maxResults) {
        this(embeddingStore, embeddingModel, maxResults, null, null);
    }

    public DynamicFilterContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                         EmbeddingModel embeddingModel,
                                         int maxResults,
                                         Double minScore) {
        this(embeddingStore, embeddingModel, maxResults, minScore, null);
    }

    public DynamicFilterContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                         EmbeddingModel embeddingModel,
                                         int maxResults,
                                         Double minScore,
                                         Filter baseFilter) {
        Assert.notNull(embeddingStore, "embeddingStore 不能为空");
        Assert.notNull(embeddingModel, "embeddingModel 不能为空");
        Assert.isTrue(maxResults > 0, "maxResults 必须大于 0");
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.baseFilter = baseFilter;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return Collections.emptyList();
        }

        // 1. 计算查询文本向量
        Embedding queryEmbedding = embeddingModel.embed(query.text()).content();

        // 2. 动态合成下推 Filter (基础 Filter + 线程动态 Filter + 租户隔离 Filter)
        Filter effectiveFilter = DynamicFilterContextHolder.resolveFilter(baseFilter);

        // 3. 构建标准检索请求并执行检索
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder requestBuilder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .filter(effectiveFilter);

        if (minScore != null) {
            requestBuilder.minScore(minScore);
        }

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(requestBuilder.build());
        if (searchResult == null || searchResult.matches() == null || searchResult.matches().isEmpty()) {
            return Collections.emptyList();
        }

        return searchResult.matches().stream()
                .map(EmbeddingMatch::embedded)
                .filter(java.util.Objects::nonNull)
                .map(Content::from)
                .toList();
    }

    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        return embeddingStore;
    }

    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public Double getMinScore() {
        return minScore;
    }

    public Filter getBaseFilter() {
        return baseFilter;
    }
}
