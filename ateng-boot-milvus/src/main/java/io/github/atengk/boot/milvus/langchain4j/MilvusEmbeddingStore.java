package io.github.atengk.boot.milvus.langchain4j;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.milvus.model.MilvusHit;
import io.github.atengk.boot.milvus.properties.MilvusConsistencyLevel;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.github.atengk.boot.milvus.tenant.MilvusTenantHelper;
import io.milvus.v2.common.IndexParam;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus 向量数据库与 LangChain4j EmbeddingStore SPI 适配器
 * 对齐 LangChain4j 官方标准契约，提供文本切片持久化、相似度语义检索与 SaaS 多租户物理/逻辑隔离
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Slf4j
@Getter
@Builder
public class MilvusEmbeddingStore implements EmbeddingStore<TextSegment> {

    private final MilvusTemplate template;

    @Builder.Default
    private final String collectionName = "langchain4j_embeddings";

    @Builder.Default
    private final String vectorFieldName = "vector";

    @Builder.Default
    private final String idFieldName = "id";

    @Builder.Default
    private final String textFieldName = "text";

    @Builder.Default
    private final String tenantFieldName = MilvusTenantHelper.DEFAULT_TENANT_FIELD;

    private final String partitionName;

    private final MilvusConsistencyLevel consistencyLevel;

    @Builder.Default
    private final int dimension = 1536;

    @Builder.Default
    private final boolean autoCreateCollection = true;

    @Builder.Default
    private volatile boolean initialized = false;

    /**
     * 确保集合与索引就绪 (开箱即用平滑自愈)
     */
    public synchronized void ensureCollectionExists() {
        if (!initialized) {
            if (autoCreateCollection && !template.hasCollection(collectionName)) {
                log.info("LangChain4j 向量存储: 集合 [{}] 不存在，正在极简初始化 (dim={})", collectionName, dimension);
                template.createCollectionAndIndex(collectionName, vectorFieldName, dimension, IndexParam.MetricType.COSINE, IndexParam.IndexType.AUTOINDEX);
            }
            initialized = true;
        }
    }

    @Override
    public String add(Embedding embedding) {
        String id = IdUtil.fastSimpleUUID();
        add(id, embedding);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        add(id, embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = IdUtil.fastSimpleUUID();
        add(id, embedding, textSegment);
        return id;
    }

    /**
     * 指定主键 ID 写入单条向量与关联文本切片
     *
     * @param id          主键 ID
     * @param embedding   文本向量
     * @param textSegment 文本切片及元数据
     */
    public void add(String id, Embedding embedding, TextSegment textSegment) {
        Assert.hasText(id, "id must not be empty");
        Assert.notNull(embedding, "embedding must not be null");
        ensureCollectionExists();

        Map<String, Object> row = new HashMap<>();
        row.put(idFieldName, id);
        row.put(vectorFieldName, embedding.vectorAsList());
        if (textSegment != null) {
            row.put(textFieldName, textSegment.text());
            if (textSegment.metadata() != null) {
                row.putAll(textSegment.metadata().toMap());
            }
        }
        MilvusTenantHelper.fillRecordTenantField(row, tenantFieldName, false);
        template.insert(collectionName, Collections.singletonList(row), partitionName);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return addAll(embeddings, null);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        if (embeddings == null || embeddings.isEmpty()) {
            return Collections.emptyList();
        }
        ensureCollectionExists();

        List<String> ids = new ArrayList<>(embeddings.size());
        List<Map<String, Object>> records = new ArrayList<>(embeddings.size());

        for (int i = 0; i < embeddings.size(); i++) {
            Embedding embedding = embeddings.get(i);
            TextSegment segment = (embedded != null && i < embedded.size()) ? embedded.get(i) : null;
            String id = IdUtil.fastSimpleUUID();
            ids.add(id);

            Map<String, Object> row = new HashMap<>();
            row.put(idFieldName, id);
            row.put(vectorFieldName, embedding.vectorAsList());
            if (segment != null) {
                row.put(textFieldName, segment.text());
                if (segment.metadata() != null) {
                    row.putAll(segment.metadata().toMap());
                }
            }
            MilvusTenantHelper.fillRecordTenantField(row, tenantFieldName, false);
            records.add(row);
        }

        template.insertBatch(collectionName, records, partitionName);
        return ids;
    }

    @Override
    public void remove(String id) {
        if (!StringUtils.hasText(id)) {
            return;
        }
        removeAll(Collections.singletonList(id));
    }

    @Override
    public void removeAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ensureCollectionExists();
        template.deleteByIds(collectionName, new ArrayList<>(ids), partitionName);
    }

    @Override
    public void removeAll(Filter filter) {
        ensureCollectionExists();
        String expr = MilvusFilterMapper.map(filter);
        if (StringUtils.hasText(expr)) {
            template.deleteByExpr(collectionName, expr, partitionName);
        }
    }

    @Override
    public void removeAll() {
        ensureCollectionExists();
        template.deleteByExpr(collectionName, String.format("%s != ''", idFieldName), partitionName);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Assert.notNull(request, "EmbeddingSearchRequest must not be null");
        ensureCollectionExists();

        Embedding queryEmbedding = request.queryEmbedding();
        Assert.notNull(queryEmbedding, "queryEmbedding must not be null");

        int maxResults = request.maxResults();
        double minScore = request.minScore();
        String filterExpr = MilvusFilterMapper.map(request.filter());

        List<MilvusHit<Map<String, Object>>> hits = template.search(
                collectionName,
                vectorFieldName,
                queryEmbedding.vectorAsList(),
                maxResults,
                filterExpr,
                null,
                consistencyLevel
        );

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        if (hits != null) {
            for (MilvusHit<Map<String, Object>> hit : hits) {
                double score = hit.getScore() != null ? hit.getScore().doubleValue() : 0.0;
                if (score < minScore) {
                    continue;
                }
                String id = hit.getId() != null ? hit.getId().toString() : "";
                Map<String, Object> rawData = hit.getRawData() != null ? hit.getRawData() : Collections.emptyMap();
                String text = (String) rawData.get(textFieldName);

                // 提取除基础列外的标量属性为 Metadata
                Map<String, Object> metaMap = new HashMap<>();
                for (Map.Entry<String, Object> entry : rawData.entrySet()) {
                    String k = entry.getKey();
                    if (!k.equals(idFieldName) && !k.equals(vectorFieldName) && !k.equals(textFieldName)) {
                        metaMap.put(k, entry.getValue());
                    }
                }
                TextSegment textSegment = text != null ? TextSegment.from(text, Metadata.from(metaMap)) : null;

                // 提取命中文档的原始向量 (若有返回)
                Embedding matchedEmbedding = null;
                Object vecObj = rawData.get(vectorFieldName);
                if (vecObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number) {
                    float[] floats = new float[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        floats[i] = ((Number) list.get(i)).floatValue();
                    }
                    matchedEmbedding = Embedding.from(floats);
                }

                matches.add(new EmbeddingMatch<>(score, id, matchedEmbedding, textSegment));
            }
        }

        return new EmbeddingSearchResult<>(matches);
    }
}
