package io.github.atengk.boot.search.template;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.get.GetResult;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import co.elastic.clients.elasticsearch.core.search.CompletionSuggestOption;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.InnerHitsResult;
import co.elastic.clients.elasticsearch.core.search.PhraseSuggestOption;
import co.elastic.clients.elasticsearch.core.search.ResponseBody;
import co.elastic.clients.elasticsearch.core.search.SourceConfig;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import co.elastic.clients.elasticsearch.core.search.TermSuggestOption;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.search.bulk.SearchBulkProcessor;
import io.github.atengk.boot.search.driver.SearchEngineDialect;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.enums.SearchHybridMode;
import io.github.atengk.boot.search.enums.SearchRefreshPolicy;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchBulkException;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.exception.SearchPaginationExceededException;
import io.github.atengk.boot.search.exception.SearchVersionConflictException;
import io.github.atengk.boot.search.metrics.SearchMetrics;
import io.github.atengk.boot.search.model.HybridSearchOptions;
import io.github.atengk.boot.search.model.OccVersion;
import io.github.atengk.boot.search.model.SearchBulkFailure;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.model.SearchInnerHit;
import io.github.atengk.boot.search.model.SearchPage;
import io.github.atengk.boot.search.model.SearchPointInTime;
import io.github.atengk.boot.search.model.SearchScrollResult;
import io.github.atengk.boot.search.model.SearchSuggestResult;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import io.github.atengk.boot.search.spi.SearchInterceptor;
import io.github.atengk.boot.search.spi.SearchInvocationContext;
import io.github.atengk.boot.search.util.SearchExceptionClassifier;
import io.github.atengk.boot.search.util.SearchMetadataHelper;
import io.github.atengk.boot.search.util.SearchTenantHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索引擎统一操作门面模板
 * <p>
 * 提供文档索引、根据 ID 查询、根据 ID 删除、局部更新（Partial Update）、未存在时 Upsert
 * 以及基于 OCC (Sequence Number & Primary Term) 的乐观并发控制闭环。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Slf4j
public class SearchTemplate {

    private final SearchEngineClient searchEngineClient;
    private final SearchIndexOperations searchIndexOperations;
    private final AtengSearchProperties properties;
    private final List<SearchInterceptor> interceptors;
    private volatile SearchMetrics searchMetrics;

    public SearchTemplate(SearchEngineClient searchEngineClient) {
        this(searchEngineClient, new AtengSearchProperties(), null, Collections.emptyList());
    }

    public SearchTemplate(SearchEngineClient searchEngineClient, AtengSearchProperties properties) {
        this(searchEngineClient, properties, null, Collections.emptyList());
    }

    public SearchTemplate(SearchEngineClient searchEngineClient, AtengSearchProperties properties, SearchMetrics searchMetrics) {
        this(searchEngineClient, properties, searchMetrics, Collections.emptyList());
    }

    public SearchTemplate(SearchEngineClient searchEngineClient,
                          AtengSearchProperties properties,
                          SearchMetrics searchMetrics,
                          List<SearchInterceptor> interceptors) {
        this.searchEngineClient = Objects.requireNonNull(searchEngineClient, "searchEngineClient must not be null");
        this.properties = properties != null ? properties : new AtengSearchProperties();
        this.searchMetrics = searchMetrics;
        this.interceptors = interceptors != null ? List.copyOf(interceptors) : Collections.emptyList();
        this.searchIndexOperations = new SearchIndexOperations(this);
    }

    public SearchMetrics getSearchMetrics() {
        return this.searchMetrics;
    }

    public void setSearchMetrics(SearchMetrics searchMetrics) {
        this.searchMetrics = searchMetrics;
    }

    /**
     * 获取专职结构面 (DDL) 索引运维门面
     *
     * @return SearchIndexOperations
     */
    public SearchIndexOperations indices() {
        return this.searchIndexOperations;
    }

    /**
     * 获取基于全局配置预设的受管流式批量缓冲处理器构建器
     *
     * @return SearchBulkProcessor.Builder 实例
     */
    public SearchBulkProcessor.Builder newBulkProcessorBuilder() {
        return SearchBulkProcessor.builder(this)
                .batchSize(properties.getBulk().getBatchSize())
                .flushInterval(properties.getBulk().getFlushInterval())
                .bufferSize(properties.getBulk().getBufferSize())
                .concurrentRequests(properties.getBulk().getConcurrentRequests())
                .maxQueueCapacity(properties.getBulk().getMaxQueueCapacity())
                .awaitCloseTimeout(properties.getBulk().getAwaitCloseTimeout())
                .maxRetries(properties.getBulk().getMaxRetries())
                .retryBackoff(properties.getBulk().getRetryBackoff());
    }

    /**
     * 基于全局配置构建一个自闭环的受管流式批量缓冲处理器
     *
     * @return SearchBulkProcessor 实例
     */
    public SearchBulkProcessor bulkProcessor() {
        return newBulkProcessorBuilder().build();
    }

    /**
     * 获取原生强类型 ElasticsearchClient 客户端
     *
     * @return ElasticsearchClient
     */
    public ElasticsearchClient getClient() {
        return searchEngineClient.getElasticsearchClient();
    }

    /**
     * 获取底层 SearchEngineClient SPI 门面
     *
     * @return SearchEngineClient
     */
    public SearchEngineClient getSearchEngineClient() {
        return searchEngineClient;
    }

    /**
     * 获取搜索引擎配置属性对象
     *
     * @return AtengSearchProperties
     */
    public AtengSearchProperties getProperties() {
        return properties;
    }

    // ==================== 1. 文档索引 (Index / Replace) ====================

    /**
     * 保存（新增或全量替换）文档（语义等价于 {@link #index(Object)}）
     *
     * @param document 待索引的文档实体
     * @param <T>      实体类型
     * @return 索引后的文档 ID
     */
    public <T> String save(T document) {
        return index(document);
    }

    /**
     * 保存（新增或全量替换）文档并指定写入刷新策略
     *
     * @param document      待索引的文档实体
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           实体类型
     * @return 索引后的文档 ID
     */
    public <T> String save(T document, SearchRefreshPolicy refreshPolicy) {
        return index(document, refreshPolicy);
    }

    /**
     * 保存（新增或全量替换）文档，支持基于 OccVersion 的乐观并发控制 (OCC)
     *
     * @param document   待索引的文档实体
     * @param occVersion 期望的 OCC 版本元数据
     * @param <T>        实体类型
     * @return 索引后的文档 ID
     */
    public <T> String save(T document, OccVersion occVersion) {
        return index(document, occVersion);
    }

    /**
     * 仅在文档不存在时创建文档（{@code op_type=create} 防覆盖写，若主键已存在触发 {@link SearchVersionConflictException}）
     *
     * @param document 待创建文档实体
     * @param <T>      实体类型
     * @return 新创建文档 ID
     */
    public <T> String createIfAbsent(T document) {
        return createIfAbsent(document, null);
    }

    /**
     * 仅在文档不存在时创建文档并指定写入刷新策略（{@code op_type=create}）
     *
     * @param document      待创建文档实体
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           实体类型
     * @return 新创建文档 ID
     */
    public <T> String createIfAbsent(T document, SearchRefreshPolicy refreshPolicy) {
        Objects.requireNonNull(document, "document must not be null");
        String index = SearchMetadataHelper.resolveIndexName(document.getClass());
        return createIfAbsent(index, document, refreshPolicy);
    }

    /**
     * 仅在指定索引中文档不存在时创建文档（{@code op_type=create}）
     *
     * @param index    目标索引名
     * @param document 待创建文档实体
     * @param <T>      实体类型
     * @return 新创建文档 ID
     */
    public <T> String createIfAbsent(String index, T document) {
        return createIfAbsent(index, document, null);
    }

    /**
     * 仅在指定索引中文档不存在时创建文档并指定写入刷新策略（{@code op_type=create}）
     *
     * @param index         目标索引名
     * @param document      待创建文档实体
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           实体类型
     * @return 新创建文档 ID
     */
    public <T> String createIfAbsent(String index, T document, SearchRefreshPolicy refreshPolicy) {
        return doIndex(index, document, null, null, refreshPolicy, true);
    }

    /**
     * 索引（新增或全量替换）文档
     * <p>
     * 自动从文档实体类上提取 @SearchIndex 索引名与 @SearchId 文档 ID。
     *
     * @param document 待索引的文档实体
     * @param <T>      实体类型
     * @return 索引后的文档 ID
     */
    public <T> String index(T document) {
        return index(document, null, null);
    }

    /**
     * 索引（新增或全量替换）文档并指定写入刷新策略
     *
     * @param document      待索引的文档实体
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           实体类型
     * @return 索引后的文档 ID
     */
    public <T> String index(T document, SearchRefreshPolicy refreshPolicy) {
        Objects.requireNonNull(document, "document must not be null");
        String index = SearchMetadataHelper.resolveIndexName(document.getClass());
        return index(index, document, null, null, refreshPolicy);
    }

    /**
     * 索引（新增或全量替换）文档，支持基于 OccVersion 的乐观并发控制 (OCC)
     *
     * @param document   待索引的文档实体
     * @param occVersion 期望的 OCC 版本元数据
     * @param <T>        实体类型
     * @return 索引后的文档 ID
     */
    public <T> String index(T document, OccVersion occVersion) {
        return index(document, occVersion != null ? occVersion.seqNo() : null, occVersion != null ? occVersion.primaryTerm() : null);
    }

    /**
     * 索引（新增或全量替换）文档，支持乐观并发控制 (OCC)
     *
     * @param document      待索引的文档实体
     * @param ifSeqNo       期望的序列号
     * @param ifPrimaryTerm 期望的分片世代
     * @param <T>           实体类型
     * @return 索引后的文档 ID
     */
    public <T> String index(T document, Long ifSeqNo, Long ifPrimaryTerm) {
        Objects.requireNonNull(document, "document must not be null");
        String index = SearchMetadataHelper.resolveIndexName(document.getClass());
        return index(index, document, ifSeqNo, ifPrimaryTerm);
    }

    /**
     * 索引（新增或全量替换）文档至指定索引
     *
     * @param index    目标索引名
     * @param document 待索引的文档实体
     * @param <T>      实体类型
     * @return 索引后的文档 ID
     */
    public <T> String index(String index, T document) {
        return index(index, document, null, null);
    }

    /**
     * 索引（新增或全量替换）文档至指定索引并指定写入刷新策略
     *
     * @param index         目标索引名
     * @param document      待索引的文档实体
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           实体类型
     * @return 索引后的文档 ID
     */
    public <T> String index(String index, T document, SearchRefreshPolicy refreshPolicy) {
        return index(index, document, null, null, refreshPolicy);
    }

    /**
     * 索引（新增或全量替换）文档至指定索引，支持基于 OccVersion 的乐观并发控制 (OCC)
     *
     * @param index      目标索引名
     * @param document   待索引的文档实体
     * @param occVersion 期望的 OCC 版本元数据
     * @param <T>        实体类型
     * @return 索引后的文档 ID
     */
    public <T> String index(String index, T document, OccVersion occVersion) {
        return index(index, document, occVersion != null ? occVersion.seqNo() : null, occVersion != null ? occVersion.primaryTerm() : null);
    }

    /**
     * 索引（新增或全量替换）文档至指定索引，支持乐观并发控制 (OCC)
     *
     * @param index         目标索引名
     * @param document      待索引的文档实体
     * @param ifSeqNo       期望的序列号
     * @param ifPrimaryTerm 期望的分片世代
     * @param <T>           实体类型
     * @return 索引后的文档 ID
     */
    public <T> String index(String index, T document, Long ifSeqNo, Long ifPrimaryTerm) {
        return index(index, document, ifSeqNo, ifPrimaryTerm, null);
    }

    /**
     * 索引（新增或全量替换）文档至指定索引，支持乐观并发控制 (OCC) 与写入刷新策略
     *
     * @param index         目标索引名
     * @param document      待索引的文档实体
     * @param ifSeqNo       期望的序列号
     * @param ifPrimaryTerm 期望的分片世代
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           实体类型
     * @return 索引后的文档 ID
     */
    public <T> String index(String index, T document, Long ifSeqNo, Long ifPrimaryTerm, SearchRefreshPolicy refreshPolicy) {
        return doIndex(index, document, ifSeqNo, ifPrimaryTerm, refreshPolicy, false);
    }

    private <T> String doIndex(String index,
                               T document,
                               Long ifSeqNo,
                               Long ifPrimaryTerm,
                               SearchRefreshPolicy refreshPolicy,
                               boolean createOnly) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(document, "document must not be null");

        String documentId = SearchMetadataHelper.resolveDocumentId(document);
        String routing = SearchMetadataHelper.resolveRouting(document);
        Class<?> entityClass = document instanceof Map<?, ?> ? null : document.getClass();

        return executeObserved(createOnly ? "createIfAbsent" : "index", index, entityClass, routing, null, false, () -> {
            try {
                Object payload = SearchTenantHelper.preparePayloadForWrite(
                        document, entityClass, properties, SearchTenantHelper.resolveObjectMapper(searchEngineClient));
                if (StringUtils.hasText(documentId)) {
                    verifySingleDocOwnershipIfStrict(index, entityClass, documentId, routing, true);
                }
                IndexResponse response = getClient().index(builder -> {
                    builder.index(index).document(payload);
                    if (StringUtils.hasText(documentId)) {
                        builder.id(documentId);
                    }
                    if (StringUtils.hasText(routing)) {
                        builder.routing(routing);
                    }
                    if (ifSeqNo != null && ifPrimaryTerm != null) {
                        builder.ifSeqNo(ifSeqNo).ifPrimaryTerm(ifPrimaryTerm);
                    }
                    if (createOnly) {
                        builder.opType(OpType.Create);
                    }
                    if (refreshPolicy != null) {
                        builder.refresh(toEsRefresh(refreshPolicy));
                    }
                    return builder;
                });
                log.debug("Indexed document [id={}] to index [{}], result: {}", response.id(), index, response.result());
                return response.id();
            } catch (Exception e) {
                if (SearchExceptionClassifier.isVersionConflict(e)) {
                    throw new SearchVersionConflictException(
                            "Document version conflict on index [" + index + "] id [" + documentId + "]: " + e.getMessage(),
                            ifSeqNo, ifPrimaryTerm, e);
                }
                if (e instanceof SearchException se) {
                    throw se;
                }
                throw new SearchException(
                        SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_INDEX_ERROR),
                        "Failed to index document [id=" + documentId + "] to index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    // ==================== 2. 文档获取 (Get) ====================

    /**
     * 根据文档 ID 查询文档实体
     * <p>
     * 自动从实体类上提取 @SearchIndex 索引名。若文档不存在或索引不存在返回 null。
     *
     * @param clazz 实体类类型
     * @param id    文档 ID
     * @param <T>   实体类型
     * @return 文档实体对象，若不存在返回 null
     */
    public <T> T getById(Class<T> clazz, String id) {
        return getById(clazz, id, null);
    }

    /**
     * 根据文档 ID 与分片路由键查询文档实体
     *
     * @param clazz   实体类类型
     * @param id      文档 ID
     * @param routing 分片路由键（若实体标注了 @SearchRouting 则必须显式传递）
     * @param <T>     实体类型
     * @return 文档实体对象，若不存在返回 null
     */
    public <T> T getById(Class<T> clazz, String id, String routing) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return getById(index, clazz, id, routing);
    }

    /**
     * 根据文档 ID 从指定索引查询文档实体
     *
     * @param index 目标索引名
     * @param clazz 实体类类型
     * @param id    文档 ID
     * @param <T>   实体类型
     * @return 文档实体对象，若不存在返回 null
     */
    public <T> T getById(String index, Class<T> clazz, String id) {
        return getById(index, clazz, id, null);
    }

    /**
     * 根据文档 ID 与分片路由键从指定索引查询文档实体
     *
     * @param index   目标索引名
     * @param clazz   实体类类型
     * @param id      文档 ID
     * @param routing 分片路由键
     * @param <T>     实体类型
     * @return 文档实体对象，若不存在返回 null
     */
    public <T> T getById(String index, Class<T> clazz, String id, String routing) {
        SearchHit<T> hit = getHitById(index, clazz, id, routing);
        return hit != null ? hit.getContent() : null;
    }

    /**
     * 根据文档 ID 查询完整命中文档模型（包含 seqNo/primaryTerm/version 等版本元数据）
     *
     * @param clazz 实体类类型
     * @param id    文档 ID
     * @param <T>   实体类型
     * @return 命中文档载荷 SearchHit，若不存在返回 null
     */
    public <T> SearchHit<T> getHitById(Class<T> clazz, String id) {
        return getHitById(clazz, id, null);
    }

    /**
     * 根据文档 ID 与分片路由键查询完整命中文档模型
     *
     * @param clazz   实体类类型
     * @param id      文档 ID
     * @param routing 分片路由键（若实体标注了 @SearchRouting 则必须显式传递）
     * @param <T>     实体类型
     * @return 命中文档载荷 SearchHit，若不存在返回 null
     */
    public <T> SearchHit<T> getHitById(Class<T> clazz, String id, String routing) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return getHitById(index, clazz, id, routing);
    }

    /**
     * 根据文档 ID 从指定索引查询完整命中文档模型
     *
     * @param index 目标索引名
     * @param clazz 实体类类型
     * @param id    文档 ID
     * @param <T>   实体类型
     * @return 命中文档载荷 SearchHit，若不存在返回 null
     */
    public <T> SearchHit<T> getHitById(String index, Class<T> clazz, String id) {
        return getHitById(index, clazz, id, null);
    }

    /**
     * 根据文档 ID 与分片路由键从指定索引查询完整命中文档模型
     *
     * @param index   目标索引名
     * @param clazz   实体类类型
     * @param id      文档 ID
     * @param routing 分片路由键（若实体标注了 @SearchRouting 且为 null 时将抛出业务异常）
     * @param <T>     实体类型
     * @return 命中文档载荷 SearchHit，若不存在返回 null
     */
    public <T> SearchHit<T> getHitById(String index, Class<T> clazz, String id, String routing) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(clazz, "clazz must not be null");
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("Document id must not be blank");
        }

        // 分片路由防漏校验：若实体标注了 @SearchRouting 但未提供 routing，执行 Fail-Fast
        if (SearchMetadataHelper.hasRoutingAnnotation(clazz) && !StringUtils.hasText(routing)) {
            throw new SearchException(SearchResultCode.SEARCH_ROUTING_REQUIRED,
                    "Routing key is required for document entity [" + clazz.getSimpleName() + "] annotated with @SearchRouting. Use getById/getHitById with routing parameter.");
        }

        return executeObserved("getById", index, clazz, routing, null, false, () -> {
            try {
                String currentTenantId = SearchTenantHelper.resolveActiveTenantId(properties, clazz);
                if (currentTenantId != null && SearchTenantHelper.isStrictMode(properties)) {
                    String tenantColumn = SearchTenantHelper.resolveTenantColumn(properties);
                    ObjectMapper clientMapper = SearchTenantHelper.resolveObjectMapper(searchEngineClient);
                    GetResponse<ObjectNode> response = getClient().get(builder -> {
                        builder.index(index).id(id);
                        if (StringUtils.hasText(routing)) {
                            builder.routing(routing);
                        }
                        return builder;
                    }, ObjectNode.class);
                    if (response.found() && response.source() != null) {
                        if (!SearchTenantHelper.isOwnedByTenant(response.source(), tenantColumn, currentTenantId)) {
                            log.debug("Blocked cross-tenant getById on index [{}] id [{}] for tenant [{}]", index, id, currentTenantId);
                            return null;
                        }
                        T entity = SearchTenantHelper.convertSourceNode(response.source(), clazz, clientMapper);
                        return buildGetSearchHit(response.id(), response.index(), entity,
                                response.seqNo(), response.primaryTerm(), response.version(), response.routing());
                    }
                    return null;
                }

                GetResponse<T> response = getClient().get(builder -> {
                    builder.index(index).id(id);
                    if (StringUtils.hasText(routing)) {
                        builder.routing(routing);
                    }
                    return builder;
                }, clazz);
                if (response.found() && response.source() != null) {
                    return buildGetSearchHit(response.id(), response.index(), response.source(),
                            response.seqNo(), response.primaryTerm(), response.version(), response.routing());
                }
                return null;
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    log.debug("Document or index not found for index [{}] and id [{}]", index, id);
                    return null;
                }
                if (e instanceof SearchException se) {
                    throw se;
                }
                throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_GET_ERROR),
                        "Failed to get document [id=" + id + "] from index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    private static <T> SearchHit<T> buildGetSearchHit(String id,
                                                      String index,
                                                      T content,
                                                      Long seqNo,
                                                      Long primaryTerm,
                                                      Long version,
                                                      String routing) {
        return SearchHit.<T>builder()
                .id(id)
                .index(index)
                .content(content)
                .seqNo(seqNo)
                .primaryTerm(primaryTerm)
                .version(version)
                .routing(routing)
                .build();
    }

    /**
     * 根据多个文档 ID 批量查询文档实体列表（可变长参数）
     * <p>
     * 自动从实体类提取 @SearchIndex 索引名；底层基于 _mget 批量端点单次往返检索；
     * 严格遵循空安全不变量，自动过滤未命中项，保底返回纯净的 List（不含 null 元素）。
     *
     * @param clazz 实体类类型
     * @param ids   文档 ID 可变参数
     * @param <T>   实体类型
     * @return 命中文档实体列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<T> getByIds(Class<T> clazz, String... ids) {
        return getByIds(clazz, ids != null ? Arrays.asList(ids) : Collections.emptyList());
    }

    /**
     * 根据多个文档 ID 批量查询文档实体列表
     * <p>
     * 自动从实体类提取 @SearchIndex 索引名；底层基于 _mget 批量端点单次往返检索；
     * 严格遵循空安全不变量，自动过滤未命中项，保底返回纯净的 List（不含 null 元素）。
     *
     * @param clazz 实体类类型
     * @param ids   文档 ID 集合
     * @param <T>   实体类型
     * @return 命中文档实体列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<T> getByIds(Class<T> clazz, Collection<String> ids) {
        return getByIds(clazz, ids, null);
    }

    /**
     * 根据多个文档 ID 与分片路由键批量查询文档实体列表
     *
     * @param clazz   实体类类型
     * @param ids     文档 ID 集合
     * @param routing 分片路由键（若实体标注了 @SearchRouting 则必须显式传递）
     * @param <T>     实体类型
     * @return 命中文档实体列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<T> getByIds(Class<T> clazz, Collection<String> ids, String routing) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return getByIds(index, clazz, ids, routing);
    }

    /**
     * 根据多个文档 ID 从指定索引批量查询文档实体列表
     *
     * @param index 目标索引名
     * @param clazz 实体类类型
     * @param ids   文档 ID 集合
     * @param <T>   实体类型
     * @return 命中文档实体列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<T> getByIds(String index, Class<T> clazz, Collection<String> ids) {
        return getByIds(index, clazz, ids, null);
    }

    /**
     * 根据多个文档 ID 与分片路由键从指定索引批量查询文档实体列表
     *
     * @param index   目标索引名
     * @param clazz   实体类类型
     * @param ids     文档 ID 集合
     * @param routing 分片路由键
     * @param <T>     实体类型
     * @return 命中文档实体列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<T> getByIds(String index, Class<T> clazz, Collection<String> ids, String routing) {
        List<SearchHit<T>> hits = getHitsByIds(index, clazz, ids, routing);
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }
        return hits.stream().map(SearchHit::getContent).filter(Objects::nonNull).toList();
    }

    /**
     * 根据多个文档 ID 批量查询完整命中文档模型列表（可变长参数）
     *
     * @param clazz 实体类类型
     * @param ids   文档 ID 可变参数
     * @param <T>   实体类型
     * @return 命中文档载荷 SearchHit 列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<SearchHit<T>> getHitsByIds(Class<T> clazz, String... ids) {
        return getHitsByIds(clazz, ids != null ? Arrays.asList(ids) : Collections.emptyList());
    }

    /**
     * 根据多个文档 ID 批量查询完整命中文档模型列表
     *
     * @param clazz 实体类类型
     * @param ids   文档 ID 集合
     * @param <T>   实体类型
     * @return 命中文档载荷 SearchHit 列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<SearchHit<T>> getHitsByIds(Class<T> clazz, Collection<String> ids) {
        return getHitsByIds(clazz, ids, null);
    }

    /**
     * 根据多个文档 ID 与分片路由键批量查询完整命中文档模型列表
     *
     * @param clazz   实体类类型
     * @param ids     文档 ID 集合
     * @param routing 分片路由键（若实体标注了 @SearchRouting 则必须显式传递）
     * @param <T>     实体类型
     * @return 命中文档载荷 SearchHit 列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<SearchHit<T>> getHitsByIds(Class<T> clazz, Collection<String> ids, String routing) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return getHitsByIds(index, clazz, ids, routing);
    }

    /**
     * 根据多个文档 ID 从指定索引批量查询完整命中文档模型列表
     *
     * @param index 目标索引名
     * @param clazz 实体类类型
     * @param ids   文档 ID 集合
     * @param <T>   实体类型
     * @return 命中文档载荷 SearchHit 列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<SearchHit<T>> getHitsByIds(String index, Class<T> clazz, Collection<String> ids) {
        return getHitsByIds(index, clazz, ids, null);
    }

    /**
     * 根据多个文档 ID 与分片路由键从指定索引批量查询完整命中文档模型列表
     *
     * @param index   目标索引名
     * @param clazz   实体类类型
     * @param ids     文档 ID 集合
     * @param routing 分片路由键
     * @param <T>     实体类型
     * @return 命中文档载荷 SearchHit 列表，若全部未命中或参数为空则返回空集合（非 null）
     */
    public <T> List<SearchHit<T>> getHitsByIds(String index, Class<T> clazz, Collection<String> ids, String routing) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(clazz, "clazz must not be null");

        // 分片路由防漏校验：若实体标注了 @SearchRouting 但未提供 routing，执行 Fail-Fast
        if (SearchMetadataHelper.hasRoutingAnnotation(clazz) && !StringUtils.hasText(routing)) {
            throw new SearchException(SearchResultCode.SEARCH_ROUTING_REQUIRED,
                    "Routing key is required for document entity [" + clazz.getSimpleName() + "] annotated with @SearchRouting. Use getByIds/getHitsByIds with routing parameter.");
        }

        if (CollectionUtil.isEmpty(ids)) {
            return Collections.emptyList();
        }
        List<String> validIds = ids.stream().filter(StringUtils::hasText).toList();
        if (validIds.isEmpty()) {
            return Collections.emptyList();
        }

        return executeObserved("getByIds", index, clazz, routing, null, false,
                () -> executeDoGetHitsByIds(index, clazz, validIds, routing));
    }

    private <T> List<SearchHit<T>> executeDoGetHitsByIds(String index, Class<T> clazz, List<String> idList, String routing) {
        try {
            String currentTenantId = SearchTenantHelper.resolveActiveTenantId(properties, clazz);
            if (currentTenantId != null && SearchTenantHelper.isStrictMode(properties)) {
                String tenantColumn = SearchTenantHelper.resolveTenantColumn(properties);
                ObjectMapper clientMapper = SearchTenantHelper.resolveObjectMapper(searchEngineClient);
                MgetResponse<ObjectNode> response = getClient().mget(b -> {
                    b.index(index).ids(idList);
                    if (StringUtils.hasText(routing)) {
                        b.routing(routing);
                    }
                    return b;
                }, ObjectNode.class);
                if (response == null || CollectionUtil.isEmpty(response.docs())) {
                    return Collections.emptyList();
                }
                List<SearchHit<T>> hits = new ArrayList<>(response.docs().size());
                for (MultiGetResponseItem<ObjectNode> item : response.docs()) {
                    if (item.isResult()) {
                        GetResult<ObjectNode> getResult = item.result();
                        if (getResult.found() && getResult.source() != null
                                && SearchTenantHelper.isOwnedByTenant(getResult.source(), tenantColumn, currentTenantId)) {
                            T entity = SearchTenantHelper.convertSourceNode(getResult.source(), clazz, clientMapper);
                            hits.add(buildGetSearchHit(getResult.id(), getResult.index(), entity,
                                    getResult.seqNo(), getResult.primaryTerm(), getResult.version(), getResult.routing()));
                        }
                    }
                }
                return hits;
            }

            MgetResponse<T> response = getClient().mget(b -> {
                b.index(index).ids(idList);
                if (StringUtils.hasText(routing)) {
                    b.routing(routing);
                }
                return b;
            }, clazz);
            if (response == null || CollectionUtil.isEmpty(response.docs())) {
                return Collections.emptyList();
            }
            List<SearchHit<T>> hits = new ArrayList<>(response.docs().size());
            for (MultiGetResponseItem<T> item : response.docs()) {
                if (item.isResult()) {
                    GetResult<T> getResult = item.result();
                    if (getResult.found() && getResult.source() != null) {
                        hits.add(buildGetSearchHit(getResult.id(), getResult.index(), getResult.source(),
                                getResult.seqNo(), getResult.primaryTerm(), getResult.version(), getResult.routing()));
                    }
                }
            }
            return hits;
        } catch (Exception e) {
            if (SearchExceptionClassifier.isNotFound(e)) {
                log.debug("Index not found during mget on index [{}]", index);
                return Collections.emptyList();
            }
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_GET_ERROR),
                    "Failed to get documents by ids from index [" + index + "]: " + e.getMessage(), e);
        }
    }

    // ==================== 2.1 文档存在性探测 (Exists) ====================

    /**
     * 根据文档 ID 判断文档是否存在
     * <p>
     * 自动从实体类上提取 @SearchIndex 索引名。通过底层 HTTP HEAD 请求快速探测，
     * 无需拉取或反序列化 _source 报文体，响应时间微秒级。若文档不存在或索引不存在返回 false。
     *
     * @param clazz 实体类类型
     * @param id    文档 ID
     * @return 文档是否存在
     */
    public boolean existsById(Class<?> clazz, String id) {
        return existsById(clazz, id, null);
    }

    /**
     * 根据文档 ID 与分片路由键判断文档是否存在
     *
     * @param clazz   实体类类型
     * @param id      文档 ID
     * @param routing 分片路由键（若实体标注了 @SearchRouting 则必须显式传递）
     * @return 文档是否存在
     */
    public boolean existsById(Class<?> clazz, String id, String routing) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return existsById(index, clazz, id, routing);
    }

    /**
     * 根据文档 ID 判断指定索引中的文档是否存在
     *
     * @param index 目标索引名
     * @param id    文档 ID
     * @return 文档是否存在
     */
    public boolean existsById(String index, String id) {
        return existsById(index, null, id, null);
    }

    /**
     * 根据文档 ID 与分片路由键判断指定索引中的文档是否存在
     *
     * @param index   目标索引名
     * @param id      文档 ID
     * @param routing 分片路由键
     * @return 文档是否存在
     */
    public boolean existsById(String index, String id, String routing) {
        return existsById(index, null, id, routing);
    }

    /**
     * 根据文档 ID 与分片路由键从指定索引判断文档是否存在
     *
     * @param index   目标索引名
     * @param clazz   实体类类型（若标注了 @SearchRouting 且 routing 为空时触发快速失败，可为 null）
     * @param id      文档 ID
     * @param routing 分片路由键
     * @return 文档是否存在
     */
    public boolean existsById(String index, Class<?> clazz, String id, String routing) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("Document id must not be blank");
        }

        // 分片路由防漏校验：若实体标注了 @SearchRouting 但未提供 routing，执行 Fail-Fast
        if (clazz != null && SearchMetadataHelper.hasRoutingAnnotation(clazz) && !StringUtils.hasText(routing)) {
            throw new SearchException(SearchResultCode.SEARCH_ROUTING_REQUIRED,
                    "Routing key is required for document entity [" + clazz.getSimpleName() + "] annotated with @SearchRouting. Use existsById with routing parameter.");
        }

        return executeObserved("existsById", index, clazz, routing, null, false, () -> {
            try {
                boolean strictTenant = SearchTenantHelper.isTenantEnforcementActive(properties, clazz)
                        && SearchTenantHelper.isStrictMode(properties);
                if (strictTenant) {
                    Query idQuery = Query.of(q -> q.ids(ids -> ids.values(id)));
                    Query scopedQuery = wrapTenantFilterIfNecessary(idQuery, clazz);
                    CountResponse response = getClient().count(builder -> {
                        builder.index(index).query(scopedQuery);
                        if (StringUtils.hasText(routing)) {
                            builder.routing(routing);
                        }
                        return builder;
                    });
                    return response.count() > 0;
                }

                BooleanResponse response = getClient().exists(builder -> {
                    builder.index(index).id(id);
                    if (StringUtils.hasText(routing)) {
                        builder.routing(routing);
                    }
                    return builder;
                });
                return response.value();
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    log.debug("Document or index not found for index [{}] and id [{}]", index, id);
                    return false;
                }
                if (e instanceof SearchException se) {
                    throw se;
                }
                throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_GET_ERROR),
                        "Failed to check document existence for id [" + id + "] on index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    // ==================== 3. 局部更新与 Upsert (Update) ====================

    /**
     * 局部更新文档字段（基于 Map）
     *
     * @param index      目标索引名
     * @param id         文档 ID
     * @param partialDoc 局部变更字段键值对
     * @return 是否成功更新
     */
    public boolean update(String index, String id, Map<String, Object> partialDoc) {
        return update(index, id, partialDoc, false, null, null);
    }

    /**
     * 局部更新文档字段，支持 docAsUpsert
     *
     * @param index       目标索引名
     * @param id          文档 ID
     * @param partialDoc  局部变更字段键值对
     * @param docAsUpsert 若文档不存在是否直接写入
     * @return 是否成功更新
     */
    public boolean update(String index, String id, Map<String, Object> partialDoc, boolean docAsUpsert) {
        return update(index, id, partialDoc, docAsUpsert, null, null);
    }

    /**
     * 局部更新文档字段，支持 docAsUpsert 与乐观并发控制 (OCC)
     *
     * @param index         目标索引名
     * @param id            文档 ID
     * @param partialDoc    局部变更字段键值对
     * @param docAsUpsert   若文档不存在是否直接写入
     * @param ifSeqNo       期望的序列号
     * @param ifPrimaryTerm 期望的分片世代
     * @return 是否成功更新
     */
    public boolean update(String index, String id, Map<String, Object> partialDoc, boolean docAsUpsert,
                          Long ifSeqNo, Long ifPrimaryTerm) {
        return update(index, id, partialDoc, docAsUpsert, OccVersion.of(ifSeqNo, ifPrimaryTerm));
    }

    /**
     * 局部更新文档字段，支持 docAsUpsert 与基于 OccVersion 的乐观并发控制 (OCC)
     *
     * @param index       目标索引名
     * @param id          文档 ID
     * @param partialDoc  局部变更字段键值对
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param occVersion  期望的 OCC 版本元数据
     * @return 是否成功更新
     */
    public boolean update(String index, String id, Map<String, Object> partialDoc, boolean docAsUpsert, OccVersion occVersion) {
        return doUpdate(index, null, id, null, partialDoc, docAsUpsert, occVersion);
    }

    /**
     * 局部更新文档字段（从实体类自动解析索引名）
     *
     * @param clazz      实体类类型
     * @param id         文档 ID
     * @param partialDoc 局部变更字段键值对
     * @return 是否成功更新
     */
    public boolean update(Class<?> clazz, String id, Map<String, Object> partialDoc) {
        return update(clazz, id, partialDoc, false, null, null);
    }

    /**
     * 局部更新文档字段（从实体类自动解析索引名），支持 docAsUpsert
     *
     * @param clazz       实体类类型
     * @param id          文档 ID
     * @param partialDoc  局部变更字段键值对
     * @param docAsUpsert 若文档不存在是否直接写入
     * @return 是否成功更新
     */
    public boolean update(Class<?> clazz, String id, Map<String, Object> partialDoc, boolean docAsUpsert) {
        return update(clazz, id, partialDoc, docAsUpsert, null, null);
    }

    /**
     * 局部更新文档字段（从实体类自动解析索引名），支持 docAsUpsert 与乐观并发控制 (OCC)
     *
     * @param clazz         实体类类型
     * @param id            文档 ID
     * @param partialDoc    局部变更字段键值对
     * @param docAsUpsert   若文档不存在是否直接写入
     * @param ifSeqNo       期望的序列号
     * @param ifPrimaryTerm 期望的分片世代
     * @return 是否成功更新
     */
    public boolean update(Class<?> clazz, String id, Map<String, Object> partialDoc, boolean docAsUpsert,
                          Long ifSeqNo, Long ifPrimaryTerm) {
        return update(clazz, id, partialDoc, docAsUpsert, OccVersion.of(ifSeqNo, ifPrimaryTerm));
    }

    /**
     * 局部更新文档字段（从实体类自动解析索引名），支持 docAsUpsert 与基于 OccVersion 的乐观并发控制 (OCC)
     *
     * @param clazz       实体类类型
     * @param id          文档 ID
     * @param partialDoc  局部变更字段键值对
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param occVersion  期望的 OCC 版本元数据
     * @return 是否成功更新
     */
    public boolean update(Class<?> clazz, String id, Map<String, Object> partialDoc, boolean docAsUpsert, OccVersion occVersion) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return doUpdate(index, clazz, id, null, partialDoc, docAsUpsert, occVersion);
    }

    /**
     * 局部更新文档字段（从实体类自动解析索引名，支持指定路由键）
     *
     * @param clazz      实体类类型
     * @param id         文档 ID
     * @param routing    分片路由键
     * @param partialDoc 局部变更字段键值对
     * @return 是否成功更新
     */
    public boolean update(Class<?> clazz, String id, String routing, Map<String, Object> partialDoc) {
        return update(clazz, id, routing, partialDoc, false, null);
    }

    /**
     * 局部更新文档字段（从实体类自动解析索引名，支持指定路由键与基于 OccVersion 的乐观并发控制）
     *
     * @param clazz       实体类类型
     * @param id          文档 ID
     * @param routing     分片路由键
     * @param partialDoc  局部变更字段键值对
     * @param occVersion  期望的 OCC 版本元数据
     * @return 是否成功更新
     */
    public boolean update(Class<?> clazz, String id, String routing, Map<String, Object> partialDoc, OccVersion occVersion) {
        return update(clazz, id, routing, partialDoc, false, occVersion);
    }

    /**
     * 局部更新文档字段（从实体类自动解析索引名，支持指定路由键、docAsUpsert 与 OCC）
     *
     * @param clazz       实体类类型
     * @param id          文档 ID
     * @param routing     分片路由键
     * @param partialDoc  局部变更字段键值对
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param occVersion  期望的 OCC 版本元数据
     * @return 是否成功更新
     */
    public boolean update(Class<?> clazz, String id, String routing, Map<String, Object> partialDoc, boolean docAsUpsert, OccVersion occVersion) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return doUpdate(index, clazz, id, routing, partialDoc, docAsUpsert, occVersion);
    }

    /**
     * 局部更新文档字段（支持指定路由键）
     *
     * @param index      目标索引名
     * @param id         文档 ID
     * @param routing    分片路由键
     * @param partialDoc 局部变更字段键值对
     * @return 是否成功更新
     */
    public boolean update(String index, String id, String routing, Map<String, Object> partialDoc) {
        return update(index, id, routing, partialDoc, false, null);
    }

    /**
     * 局部更新文档字段（支持指定路由键），支持 docAsUpsert 与基于 OccVersion 的乐观并发控制 (OCC)
     *
     * @param index       目标索引名
     * @param id          文档 ID
     * @param routing     分片路由键
     * @param partialDoc  局部变更字段键值对
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param occVersion  期望的 OCC 版本元数据
     * @return 是否成功更新
     */
    public boolean update(String index, String id, String routing, Map<String, Object> partialDoc, boolean docAsUpsert, OccVersion occVersion) {
        return doUpdate(index, null, id, routing, partialDoc, docAsUpsert, occVersion, null);
    }

    /**
     * 局部更新文档字段（指定写入刷新策略）
     *
     * @param clazz         实体类类型
     * @param id            文档 ID
     * @param routing       分片路由键（可为 null）
     * @param partialDoc    局部变更字段键值对
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 是否成功更新
     */
    public boolean update(Class<?> clazz, String id, String routing, Map<String, Object> partialDoc, SearchRefreshPolicy refreshPolicy) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return doUpdate(index, clazz, id, routing, partialDoc, false, null, refreshPolicy);
    }

    /**
     * 局部更新文档字段（指定索引与写入刷新策略）
     *
     * @param index         目标索引名
     * @param id            文档 ID
     * @param partialDoc    局部变更字段键值对
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 是否成功更新
     */
    public boolean update(String index, String id, Map<String, Object> partialDoc, SearchRefreshPolicy refreshPolicy) {
        return doUpdate(index, null, id, null, partialDoc, false, null, refreshPolicy);
    }

    /**
     * 局部更新文档实体字段
     *
     * @param index       目标索引名
     * @param id          文档 ID
     * @param partialDoc  局部变更实体
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param <T>         实体类型
     * @return 是否成功更新
     */
    public <T> boolean update(String index, String id, T partialDoc, boolean docAsUpsert) {
        return update(index, id, partialDoc, docAsUpsert, null, null);
    }

    /**
     * 局部更新文档实体字段，支持 docAsUpsert 与乐观并发控制 (OCC)
     *
     * @param index         目标索引名
     * @param id            文档 ID
     * @param partialDoc    局部变更实体
     * @param docAsUpsert   若文档不存在是否直接写入
     * @param ifSeqNo       期望的序列号
     * @param ifPrimaryTerm 期望的分片世代
     * @param <T>           实体类型
     * @return 是否成功更新
     */
    public <T> boolean update(String index, String id, T partialDoc, boolean docAsUpsert,
                              Long ifSeqNo, Long ifPrimaryTerm) {
        return update(index, id, partialDoc, docAsUpsert, OccVersion.of(ifSeqNo, ifPrimaryTerm));
    }

    /**
     * 局部更新文档实体字段，支持 docAsUpsert 与基于 OccVersion 的乐观并发控制 (OCC)
     *
     * @param index       目标索引名
     * @param id          文档 ID
     * @param partialDoc  局部变更实体
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param occVersion  期望的 OCC 版本元数据
     * @param <T>         实体类型
     * @return 是否成功更新
     */
    public <T> boolean update(String index, String id, T partialDoc, boolean docAsUpsert, OccVersion occVersion) {
        return doUpdate(index, null, id, null, partialDoc, docAsUpsert, occVersion);
    }

    /**
     * 局部更新文档实体字段（自动提取 @SearchIndex 索引名与 @SearchId 文档 ID）
     *
     * @param partialDoc 局部变更实体
     * @param <T>        实体类型
     * @return 是否成功更新
     */
    public <T> boolean update(T partialDoc) {
        return update(partialDoc, false, null, null);
    }

    /**
     * 局部更新文档实体字段，支持 docAsUpsert
     *
     * @param partialDoc  局部变更实体
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param <T>         实体类型
     * @return 是否成功更新
     */
    public <T> boolean update(T partialDoc, boolean docAsUpsert) {
        return update(partialDoc, docAsUpsert, null, null);
    }

    /**
     * 局部更新文档实体字段，支持 docAsUpsert 与乐观并发控制 (OCC)
     *
     * @param partialDoc    局部变更实体
     * @param docAsUpsert   若文档不存在是否直接写入
     * @param ifSeqNo       期望的序列号
     * @param ifPrimaryTerm 期望的分片世代
     * @param <T>           实体类型
     * @return 是否成功更新
     */
    public <T> boolean update(T partialDoc, boolean docAsUpsert, Long ifSeqNo, Long ifPrimaryTerm) {
        return update(partialDoc, docAsUpsert, OccVersion.of(ifSeqNo, ifPrimaryTerm));
    }

    /**
     * 局部更新文档实体字段，支持 docAsUpsert 与基于 OccVersion 的乐观并发控制 (OCC)
     *
     * @param partialDoc  局部变更实体
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param occVersion  期望的 OCC 版本元数据
     * @param <T>         实体类型
     * @return 是否成功更新
     */
    public <T> boolean update(T partialDoc, boolean docAsUpsert, OccVersion occVersion) {
        Objects.requireNonNull(partialDoc, "partialDoc must not be null");
        String index = SearchMetadataHelper.resolveIndexName(partialDoc.getClass());
        String documentId = SearchMetadataHelper.resolveDocumentId(partialDoc);
        if (!StringUtils.hasText(documentId)) {
            throw new IllegalArgumentException("Cannot update document without an ID. Ensure @SearchId is configured on the entity.");
        }
        return update(index, documentId, partialDoc, docAsUpsert, occVersion);
    }

    private <T> boolean doUpdate(String index,
                                 Class<?> explicitEntityClass,
                                 String id,
                                 String routing,
                                 T partialDoc,
                                 boolean docAsUpsert,
                                 OccVersion occVersion) {
        return doUpdate(index, explicitEntityClass, id, routing, partialDoc, docAsUpsert, occVersion, null);
    }

    private <T> boolean doUpdate(String index,
                                 Class<?> explicitEntityClass,
                                 String id,
                                 String routing,
                                 T partialDoc,
                                 boolean docAsUpsert,
                                 OccVersion occVersion,
                                 SearchRefreshPolicy refreshPolicy) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("Document id must not be blank");
        }
        Objects.requireNonNull(partialDoc, "partialDoc must not be null");

        String resolvedRouting = StringUtils.hasText(routing) ? routing : SearchMetadataHelper.resolveRouting(partialDoc);
        Class<?> entityClass = explicitEntityClass != null
                ? explicitEntityClass
                : (partialDoc instanceof Map<?, ?> ? null : partialDoc.getClass());

        return executeObserved("update", index, entityClass, resolvedRouting, null, false, () -> {
            try {
                Object payload = SearchTenantHelper.preparePayloadForWrite(
                        partialDoc, entityClass, properties, SearchTenantHelper.resolveObjectMapper(searchEngineClient));
                if (!verifySingleDocOwnershipIfStrict(index, entityClass, id, resolvedRouting, docAsUpsert)) {
                    return false;
                }
                UpdateResponse<Void> response = getClient().update(builder -> {
                    builder.index(index)
                            .id(id)
                            .doc(payload)
                            .docAsUpsert(docAsUpsert);
                    if (StringUtils.hasText(resolvedRouting)) {
                        builder.routing(resolvedRouting);
                    }
                    if (occVersion != null && occVersion.isSpecified()) {
                        builder.ifSeqNo(occVersion.seqNo()).ifPrimaryTerm(occVersion.primaryTerm());
                    }
                    if (refreshPolicy != null) {
                        builder.refresh(toEsRefresh(refreshPolicy));
                    }
                    return builder;
                }, Void.class);

                log.debug("Updated document [id={}] on index [{}], result: {}", id, index, response.result());
                return response.result() == Result.Updated || response.result() == Result.Created || response.result() == Result.NoOp;
            } catch (Exception e) {
                if (SearchExceptionClassifier.isVersionConflict(e)) {
                    throw new SearchVersionConflictException(
                            "Document version conflict on update for index [" + index + "] id [" + id + "]: " + e.getMessage(),
                            occVersion != null ? occVersion.seqNo() : null,
                            occVersion != null ? occVersion.primaryTerm() : null,
                            e);
                }
                if (SearchExceptionClassifier.isNotFound(e)) {
                    log.debug("Document or index not found for update on index [{}] and id [{}]", index, id);
                    return false;
                }
                if (e instanceof SearchException se) {
                    throw se;
                }
                throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to update document [id=" + id + "] on index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    // ==================== 4. 文档删除 (Delete) ====================

    /**
     * 根据文档 ID 删除文档
     * <p>
     * 自动从实体类上提取 @SearchIndex 索引名。
     *
     * @param clazz 实体类类型
     * @param id    文档 ID
     * @return 是否成功删除（若文档不存在则返回 false）
     */
    public boolean deleteById(Class<?> clazz, String id) {
        return deleteById(clazz, id, null);
    }

    /**
     * 根据文档 ID 与分片路由键删除文档
     *
     * @param clazz   实体类类型
     * @param id      文档 ID
     * @param routing 分片路由键（若实体标注了 @SearchRouting 则必须显式传递）
     * @return 是否成功删除（若文档不存在则返回 false）
     */
    public boolean deleteById(Class<?> clazz, String id, String routing) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        if (SearchMetadataHelper.hasRoutingAnnotation(clazz) && !StringUtils.hasText(routing)) {
            throw new SearchException(SearchResultCode.SEARCH_ROUTING_REQUIRED,
                    "Routing key is required for document entity [" + clazz.getSimpleName() + "] annotated with @SearchRouting. Use deleteById with routing parameter.");
        }
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return doDeleteById(index, clazz, id, routing);
    }

    /**
     * 根据文档 ID 从指定索引删除文档
     *
     * @param index 目标索引名
     * @param id    文档 ID
     * @return 是否成功删除（若文档不存在则返回 false）
     */
    public boolean deleteById(String index, String id) {
        return deleteById(index, id, null);
    }

    /**
     * 根据文档 ID 与分片路由键从指定索引删除文档
     *
     * @param index   目标索引名
     * @param id      文档 ID
     * @param routing 分片路由键
     * @return 是否成功删除（若文档不存在则返回 false）
     */
    public boolean deleteById(String index, String id, String routing) {
        return doDeleteById(index, null, id, routing, null);
    }

    /**
     * 根据文档 ID 与分片路由键删除文档并指定写入刷新策略
     *
     * @param clazz         实体类类型
     * @param id            文档 ID
     * @param routing       分片路由键（可为 null）
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 是否成功删除
     */
    public boolean deleteById(Class<?> clazz, String id, String routing, SearchRefreshPolicy refreshPolicy) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        if (SearchMetadataHelper.hasRoutingAnnotation(clazz) && !StringUtils.hasText(routing)) {
            throw new SearchException(SearchResultCode.SEARCH_ROUTING_REQUIRED,
                    "Routing key is required for document entity [" + clazz.getSimpleName() + "] annotated with @SearchRouting. Use deleteById with routing parameter.");
        }
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return doDeleteById(index, clazz, id, routing, refreshPolicy);
    }

    /**
     * 根据文档 ID 与分片路由键从指定索引删除文档并指定写入刷新策略
     *
     * @param index         目标索引名
     * @param id            文档 ID
     * @param routing       分片路由键（可为 null）
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 是否成功删除
     */
    public boolean deleteById(String index, String id, String routing, SearchRefreshPolicy refreshPolicy) {
        return doDeleteById(index, null, id, routing, refreshPolicy);
    }

    private boolean doDeleteById(String index, Class<?> entityClass, String id, String routing) {
        return doDeleteById(index, entityClass, id, routing, null);
    }

    private boolean doDeleteById(String index, Class<?> entityClass, String id, String routing, SearchRefreshPolicy refreshPolicy) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("Document id must not be blank");
        }

        return executeObserved("deleteById", index, entityClass, routing, null, false, () -> {
            try {
                if (!verifySingleDocOwnershipIfStrict(index, entityClass, id, routing, false)) {
                    return false;
                }
                DeleteResponse response = getClient().delete(builder -> {
                    builder.index(index).id(id);
                    if (StringUtils.hasText(routing)) {
                        builder.routing(routing);
                    }
                    if (refreshPolicy != null) {
                        builder.refresh(toEsRefresh(refreshPolicy));
                    }
                    return builder;
                });
                log.debug("Deleted document [id={}] from index [{}], result: {}", id, index, response.result());
                return response.result() == Result.Deleted;
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    log.debug("Document or index not found for deletion on index [{}] and id [{}]", index, id);
                    return false;
                }
                if (e instanceof SearchException se) {
                    throw se;
                }
                throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_DELETE_ERROR),
                        "Failed to delete document [id=" + id + "] from index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    private boolean verifySingleDocOwnershipIfStrict(String index,
                                                     Class<?> entityClass,
                                                     String id,
                                                     String routing,
                                                     boolean allowMissing) {
        if (!SearchTenantHelper.isStrictMode(properties)) {
            return true;
        }
        String currentTenantId = SearchTenantHelper.resolveActiveTenantId(properties, entityClass);
        if (currentTenantId == null) {
            return true;
        }
        String tenantColumn = SearchTenantHelper.resolveTenantColumn(properties);
        try {
            GetResponse<ObjectNode> ownershipResp = getClient().get(builder -> {
                builder.index(index).id(id).sourceIncludes(tenantColumn);
                if (StringUtils.hasText(routing)) {
                    builder.routing(routing);
                }
                return builder;
            }, ObjectNode.class);
            if (!ownershipResp.found() || ownershipResp.source() == null) {
                return allowMissing;
            }
            if (!SearchTenantHelper.isOwnedByTenant(ownershipResp.source(), tenantColumn, currentTenantId)) {
                throw new SearchException(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED,
                        "Cross-tenant mutation blocked on index [" + index + "] id [" + id + "] for tenant [" + currentTenantId + "]");
            }
            return true;
        } catch (SearchException se) {
            throw se;
        } catch (Exception e) {
            if (SearchExceptionClassifier.isNotFound(e)) {
                return allowMissing;
            }
            throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                    "Failed to verify tenant ownership on index [" + index + "] id [" + id + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 根据查询规格条件批量删除文档
     * <p>
     * 自动从实体类解析 @SearchIndex 索引名；多租户激活时自动隐式注入租户隔离过滤（标注 @IgnoreTenant 的实体除外）；
     * 默认配置 conflicts 为 Proceed，高并发版本冲突时不中断批量任务。
     *
     * @param query 查询规格对象（不得为 null）
     * @param clazz 实体类类型（不得为 null）
     * @return 成功删除的文档数量
     */
    public long deleteByQuery(SearchQuery query, Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(query, "query must not be null");
        String index = CollectionUtil.isNotEmpty(query.getIndices())
                ? String.join(",", query.getIndices())
                : SearchMetadataHelper.resolveIndexName(clazz);
        return deleteByQuery(index, query, clazz);
    }

    /**
     * 在指定索引上根据查询规格条件批量删除文档
     *
     * @param index 目标索引名
     * @param query 查询规格对象（不得为 null）
     * @return 成功删除的文档数量
     */
    public long deleteByQuery(String index, SearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return deleteByQuery(index, query, null);
    }

    /**
     * 在指定索引上根据查询规格条件批量删除文档，支持实体映射规则与多租户过滤
     *
     * @param index 目标索引名
     * @param query 查询规格对象（不得为 null）
     * @param clazz 实体类类型（用于多租户隐式过滤等元数据解析，可为 null）
     * @return 成功删除的文档数量
     */
    public long deleteByQuery(String index, SearchQuery query, Class<?> clazz) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(query, "query must not be null");
        return executeObserved("deleteByQuery", index, clazz, query.getRouting(), query, false, () -> {
            DeleteByQueryResponse response = executeDoDeleteByQuery(index, query, clazz, true);
            return response != null && response.deleted() != null ? response.deleted() : 0L;
        });
    }

    /**
     * 异步发起条件物理删除任务并立即返回后台 TaskId（不阻塞等待完成）
     *
     * @param query 查询规格对象
     * @param clazz 实体类类型
     * @return 异步任务唯一标识 {@code nodeId:taskId}（索引不存在时返回 null）
     */
    public String deleteByQueryAsync(SearchQuery query, Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(query, "query must not be null");
        String index = CollectionUtil.isNotEmpty(query.getIndices())
                ? String.join(",", query.getIndices())
                : SearchMetadataHelper.resolveIndexName(clazz);
        return deleteByQueryAsync(index, query, clazz);
    }

    /**
     * 在指定索引上异步发起条件物理删除任务并立即返回后台 TaskId
     *
     * @param index 目标索引名
     * @param query 查询规格对象
     * @return 异步任务唯一标识 {@code nodeId:taskId}（索引不存在时返回 null）
     */
    public String deleteByQueryAsync(String index, SearchQuery query) {
        return deleteByQueryAsync(index, query, null);
    }

    /**
     * 在指定索引上异步发起条件物理删除任务并立即返回后台 TaskId
     *
     * @param index 目标索引名
     * @param query 查询规格对象
     * @param clazz 实体类类型（可为 null）
     * @return 异步任务唯一标识 {@code nodeId:taskId}（索引不存在时返回 null）
     */
    public String deleteByQueryAsync(String index, SearchQuery query, Class<?> clazz) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(query, "query must not be null");
        return executeObserved("deleteByQueryAsync", index, clazz, query.getRouting(), query, false, () -> {
            DeleteByQueryResponse response = executeDoDeleteByQuery(index, query, clazz, false);
            return response != null ? response.task() : null;
        });
    }

    private DeleteByQueryResponse executeDoDeleteByQuery(String index,
                                                         SearchQuery query,
                                                         Class<?> clazz,
                                                         boolean waitForCompletion) {
        try {
            Query finalQuery = resolveTenantScopedQuery(query, clazz);

            DeleteByQueryResponse response = getClient().deleteByQuery(builder -> {
                builder.index(index)
                        .query(finalQuery)
                        .conflicts(Conflicts.Proceed)
                        .waitForCompletion(waitForCompletion);
                if (query != null && StringUtils.hasText(query.getRouting())) {
                    builder.routing(query.getRouting());
                }
                return builder;
            });
            log.debug("Executed deleteByQuery on index [{}], deleted: {}, task: {}, versionConflicts: {}",
                    index, response.deleted(), response.task(), response.versionConflicts());
            return response;
        } catch (Exception e) {
            if (SearchExceptionClassifier.isNotFound(e)) {
                log.debug("Index not found during deleteByQuery on index [{}]", index);
                return null;
            }
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_DELETE_ERROR),
                    "Failed to execute deleteByQuery on index [" + index + "]: " + e.getMessage(), e);
        }
    }

    // ==================== 4.1 条件驱动批量更新 (UpdateByQuery) ====================

    private static final Pattern SAFE_FIELD_PATH_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$");

    /**
     * 基于参数化安全脚本按查询条件批量更新文档字段（自动注入多租户隔离）
     *
     * @param query         查询规格对象
     * @param partialFields 待更新字段键值对（编译为参数化 Painless 脚本，严防脚本注入）
     * @param clazz         实体类类型
     * @return 成功更新的文档数量
     */
    public long updateByQuery(SearchQuery query, Map<String, Object> partialFields, Class<?> clazz) {
        return updateByQuery(query, partialFields, clazz, null);
    }

    /**
     * 基于参数化安全脚本按查询条件批量更新文档字段并指定刷新策略
     *
     * @param query         查询规格对象
     * @param partialFields 待更新字段键值对
     * @param clazz         实体类类型
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 成功更新的文档数量
     */
    public long updateByQuery(SearchQuery query,
                              Map<String, Object> partialFields,
                              Class<?> clazz,
                              SearchRefreshPolicy refreshPolicy) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(query, "query must not be null");
        String index = CollectionUtil.isNotEmpty(query.getIndices())
                ? String.join(",", query.getIndices())
                : SearchMetadataHelper.resolveIndexName(clazz);
        return updateByQuery(index, query, partialFields, clazz, refreshPolicy);
    }

    /**
     * 在指定索引上基于参数化安全脚本按查询条件批量更新文档字段
     *
     * @param index         目标索引名
     * @param query         查询规格对象
     * @param partialFields 待更新字段键值对
     * @param clazz         实体类类型（可为 null）
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 成功更新的文档数量
     */
    public long updateByQuery(String index,
                              SearchQuery query,
                              Map<String, Object> partialFields,
                              Class<?> clazz,
                              SearchRefreshPolicy refreshPolicy) {
        Script script = buildParameterizedUpdateScript(partialFields, clazz);
        return executeUpdateByQuery(index, query, script, clazz, refreshPolicy);
    }

    /**
     * 在指定索引上基于显式 Painless 脚本按查询条件批量更新文档（脚本逃生舱，仍强制注入多租户隔离并拦截租户列改写）
     *
     * @param index          目标索引名
     * @param query          查询规格对象
     * @param painlessScript Painless 源码脚本
     * @param params         脚本参数映射（可为 null）
     * @param clazz          实体类类型（可为 null）
     * @param refreshPolicy  写入刷新策略（可为 null）
     * @return 成功更新的文档数量
     */
    public long updateByQuery(String index,
                              SearchQuery query,
                              String painlessScript,
                              Map<String, Object> params,
                              Class<?> clazz,
                              SearchRefreshPolicy refreshPolicy) {
        if (!StringUtils.hasText(painlessScript)) {
            throw new IllegalArgumentException("painlessScript must not be blank");
        }
        rejectCrossTenantPainlessMutation(painlessScript, clazz);
        Map<String, JsonData> jsonParams = toScriptJsonParams(params);
        Script script = Script.of(s -> s.inline(in -> {
            in.lang("painless").source(painlessScript);
            if (!jsonParams.isEmpty()) {
                in.params(jsonParams);
            }
            return in;
        }));
        return executeUpdateByQuery(index, query, script, clazz, refreshPolicy);
    }

    private long executeUpdateByQuery(String index,
                                      SearchQuery query,
                                      Script script,
                                      Class<?> clazz,
                                      SearchRefreshPolicy refreshPolicy) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(query, "query must not be null");
        return executeObserved("updateByQuery", index, clazz, query.getRouting(), query, false, () -> {
            UpdateByQueryResponse response = executeDoUpdateByQuery(index, query, script, clazz, refreshPolicy, true);
            return response != null && response.updated() != null ? response.updated() : 0L;
        });
    }

    /**
     * 基于参数化安全脚本异步发起条件批量更新任务并立即返回 TaskId（自动注入多租户隔离）
     *
     * @param query         查询规格对象
     * @param partialFields 待更新字段键值对
     * @param clazz         实体类类型
     * @return 异步任务唯一标识 {@code nodeId:taskId}（索引不存在时返回 null）
     */
    public String updateByQueryAsync(SearchQuery query, Map<String, Object> partialFields, Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(query, "query must not be null");
        String index = CollectionUtil.isNotEmpty(query.getIndices())
                ? String.join(",", query.getIndices())
                : SearchMetadataHelper.resolveIndexName(clazz);
        return updateByQueryAsync(index, query, partialFields, clazz);
    }

    /**
     * 在指定索引上基于参数化安全脚本异步发起条件批量更新任务并立即返回 TaskId
     *
     * @param index         目标索引名
     * @param query         查询规格对象
     * @param partialFields 待更新字段键值对
     * @return 异步任务唯一标识 {@code nodeId:taskId}（索引不存在时返回 null）
     */
    public String updateByQueryAsync(String index, SearchQuery query, Map<String, Object> partialFields) {
        return updateByQueryAsync(index, query, partialFields, null);
    }

    /**
     * 在指定索引上基于参数化安全脚本异步发起条件批量更新任务并立即返回 TaskId
     *
     * @param index         目标索引名
     * @param query         查询规格对象
     * @param partialFields 待更新字段键值对
     * @param clazz         实体类类型（可为 null）
     * @return 异步任务唯一标识 {@code nodeId:taskId}（索引不存在时返回 null）
     */
    public String updateByQueryAsync(String index,
                                     SearchQuery query,
                                     Map<String, Object> partialFields,
                                     Class<?> clazz) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(query, "query must not be null");
        Script script = buildParameterizedUpdateScript(partialFields, clazz);
        return executeObserved("updateByQueryAsync", index, clazz, query.getRouting(), query, false, () -> {
            UpdateByQueryResponse response = executeDoUpdateByQuery(index, query, script, clazz, null, false);
            return response != null ? response.task() : null;
        });
    }

    private UpdateByQueryResponse executeDoUpdateByQuery(String index,
                                                         SearchQuery query,
                                                         Script script,
                                                         Class<?> clazz,
                                                         SearchRefreshPolicy refreshPolicy,
                                                         boolean waitForCompletion) {
        try {
            Query finalQuery = resolveTenantScopedQuery(query, clazz);

            UpdateByQueryResponse response = getClient().updateByQuery(builder -> {
                builder.index(index)
                        .query(finalQuery)
                        .script(script)
                        .conflicts(Conflicts.Proceed)
                        .waitForCompletion(waitForCompletion);
                if (query != null && StringUtils.hasText(query.getRouting())) {
                    builder.routing(query.getRouting());
                }
                if (refreshPolicy != null) {
                    builder.refresh(refreshPolicy.toBooleanRefresh());
                }
                return builder;
            });
            log.debug("Executed updateByQuery on index [{}], updated: {}, task: {}, versionConflicts: {}",
                    index, response.updated(), response.task(), response.versionConflicts());
            return response;
        } catch (Exception e) {
            if (SearchExceptionClassifier.isNotFound(e)) {
                log.debug("Index not found during updateByQuery on index [{}]", index);
                return null;
            }
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                    "Failed to execute updateByQuery on index [" + index + "]: " + e.getMessage(), e);
        }
    }

    private Query resolveTenantScopedQuery(SearchQuery query, Class<?> clazz) {
        Query baseQuery = query != null ? query.getQuery() : null;
        SearchTenantHelper.rejectCrossTenantQueryFilter(
                baseQuery, clazz, properties, SearchTenantHelper.resolveObjectMapper(searchEngineClient));
        Query effectiveQuery = wrapTenantFilterIfNecessary(baseQuery, clazz);
        return effectiveQuery != null ? effectiveQuery : Query.of(q -> q.matchAll(m -> m));
    }

    private void rejectCrossTenantPainlessMutation(String painlessScript, Class<?> clazz) {
        String activeTenantId = SearchTenantHelper.resolveActiveTenantId(properties, clazz);
        if (activeTenantId == null) {
            return;
        }
        String tenantColumn = SearchTenantHelper.resolveTenantColumn(properties);
        String quotedColumns = Pattern.quote("tenantId") + "|" + Pattern.quote("tenant_id")
                + (StringUtils.hasText(tenantColumn) ? "|" + Pattern.quote(tenantColumn) : "");
        Pattern tenantMutationPattern = Pattern.compile(
                "(?is)(?:ctx\\s*\\.\\s*_source\\s*(?:\\.\\s*(?:" + quotedColumns + ")\\b|\\[\\s*['\"](?:" + quotedColumns + ")['\"]\\s*])\\s*=|ctx\\s*\\.\\s*_source\\s*\\.\\s*put\\s*\\(\\s*['\"](?:" + quotedColumns + ")['\"])"
        );
        if (tenantMutationPattern.matcher(painlessScript).find()) {
            throw new SearchException(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED,
                    "Cross-tenant field mutation blocked in explicit Painless script for tenant [" + activeTenantId + "]");
        }
    }

    private static Refresh toEsRefresh(SearchRefreshPolicy policy) {
        if (policy == null) {
            return null;
        }
        return switch (policy) {
            case IMMEDIATE -> Refresh.True;
            case WAIT_UNTIL -> Refresh.WaitFor;
            case NONE -> Refresh.False;
        };
    }

    private Script buildParameterizedUpdateScript(Map<String, Object> partialFields, Class<?> clazz) {
        if (partialFields == null || partialFields.isEmpty()) {
            throw new IllegalArgumentException("partialFields must not be empty");
        }
        String activeTenantId = SearchTenantHelper.resolveActiveTenantId(properties, clazz);
        String tenantColumn = SearchTenantHelper.resolveTenantColumn(properties);
        StringBuilder source = new StringBuilder();
        Map<String, JsonData> params = new LinkedHashMap<>();
        int idx = 0;
        for (Map.Entry<String, Object> entry : partialFields.entrySet()) {
            String field = entry.getKey();
            if (!StringUtils.hasText(field) || !SAFE_FIELD_PATH_PATTERN.matcher(field).matches()) {
                throw new IllegalArgumentException("Unsafe or invalid field path in updateByQuery: [" + field + "]");
            }
            if (activeTenantId != null && (field.equals(tenantColumn) || "tenantId".equals(field) || "tenant_id".equals(field))) {
                String incomingVal = entry.getValue() != null ? entry.getValue().toString().trim() : null;
                if (!activeTenantId.equals(incomingVal)) {
                    throw new SearchException(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED,
                            "Cross-tenant field mutation blocked in updateByQuery for tenant [" + activeTenantId + "]");
                }
            }
            String paramKey = "p" + idx++;
            String[] segments = field.split("\\.");
            source.append("ctx._source");
            for (String segment : segments) {
                source.append("['").append(segment).append("']");
            }
            source.append(" = params['").append(paramKey).append("']; ");
            params.put(paramKey, JsonData.of(entry.getValue()));
        }
        String scriptText = source.toString().trim();
        return Script.of(s -> s.inline(in -> in.lang("painless").source(scriptText).params(params)));
    }

    private static Map<String, JsonData> toScriptJsonParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, JsonData> result = new LinkedHashMap<>(params.size());
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (StringUtils.hasText(entry.getKey())) {
                result.put(entry.getKey(), JsonData.of(entry.getValue()));
            }
        }
        return result;
    }

    // ==================== 5. 双模批量写入治理 (Bulk Operations) ====================

    /**
     * 批量索引文档（严格快速失败模式）
     * <p>
     * 自动从各文档实体类解析 @SearchIndex 索引名与 @SearchId 文档 ID。
     * 若底层批量操作存在任一文档失败，立即抛出 {@link SearchBulkException} 阻断脏数据扩散。
     *
     * @param documents 待索引文档集合
     * @param <T>       文档实体类型
     * @return 批量操作结果模型（全量成功时返回）
     * @throws SearchBulkException 当任一文档写入失败时抛出
     */
    public <T> SearchBulkResult indexBatch(Collection<T> documents) {
        return indexBatch(null, documents);
    }

    /**
     * 批量索引文档至指定索引（严格快速失败模式）
     * <p>
     * 若底层批量操作存在任一文档失败，立即抛出 {@link SearchBulkException} 阻断脏数据扩散。
     *
     * @param index     目标索引名（为 null 时自动解析各实体类注解）
     * @param documents 待索引文档集合
     * @param <T>       文档实体类型
     * @return 批量操作结果模型（全量成功时返回）
     * @throws SearchBulkException 当任一文档写入失败时抛出
     */
    public <T> SearchBulkResult indexBatch(String index, Collection<T> documents) {
        return doBulkIndex(index, documents, false, null);
    }

    /**
     * 批量索引文档并指定写入刷新策略（严格快速失败模式）
     *
     * @param documents     待索引文档集合
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           文档实体类型
     * @return 批量操作结果模型
     */
    public <T> SearchBulkResult indexBatch(Collection<T> documents, SearchRefreshPolicy refreshPolicy) {
        return doBulkIndex(null, documents, false, refreshPolicy);
    }

    /**
     * 批量索引文档至指定索引并指定写入刷新策略（严格快速失败模式）
     *
     * @param index         目标索引名（为 null 时自动解析各实体类注解）
     * @param documents     待索引文档集合
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           文档实体类型
     * @return 批量操作结果模型
     */
    public <T> SearchBulkResult indexBatch(String index, Collection<T> documents, SearchRefreshPolicy refreshPolicy) {
        return doBulkIndex(index, documents, false, refreshPolicy);
    }

    /**
     * 批量索引文档（宽容模式）
     * <p>
     * 不向外抛出批量局部失败异常，将全部执行结果结构化封装进 {@link SearchBulkResult} 返回，
     * 专供数据管道同步、死信队列隔离与局部补偿重试场景。
     *
     * @param documents 待索引文档集合
     * @param <T>       文档实体类型
     * @return 批量操作结果模型（包含成功数与失败列表快照）
     */
    public <T> SearchBulkResult indexBatchLenient(Collection<T> documents) {
        return indexBatchLenient(null, documents);
    }

    /**
     * 批量索引文档至指定索引（宽容模式）
     *
     * @param index     目标索引名（为 null 时自动解析各实体类注解）
     * @param documents 待索引文档集合
     * @param <T>       文档实体类型
     * @return 批量操作结果模型（包含成功数与失败列表快照）
     */
    public <T> SearchBulkResult indexBatchLenient(String index, Collection<T> documents) {
        return doBulkIndex(index, documents, true, null);
    }

    /**
     * 批量索引文档并指定写入刷新策略（宽容模式）
     *
     * @param index         目标索引名（为 null 时自动解析各实体类注解）
     * @param documents     待索引文档集合
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           文档实体类型
     * @return 批量操作结果模型
     */
    public <T> SearchBulkResult indexBatchLenient(String index, Collection<T> documents, SearchRefreshPolicy refreshPolicy) {
        return doBulkIndex(index, documents, true, refreshPolicy);
    }

    /**
     * 批量局部更新文档（严格快速失败模式）
     * <p>
     * 自动从各文档实体类解析 @SearchIndex 索引名与 @SearchId 文档 ID。
     *
     * @param partialDocs 局部变更实体集合
     * @param <T>         文档实体类型
     * @return 批量操作结果模型（全量成功时返回）
     * @throws SearchBulkException 当任一文档更新失败时抛出
     */
    public <T> SearchBulkResult updateBatch(Collection<T> partialDocs) {
        return updateBatch(null, partialDocs, false);
    }

    /**
     * 批量局部更新文档，支持 docAsUpsert（严格快速失败模式）
     *
     * @param index       目标索引名（为 null 时自动解析实体注解）
     * @param partialDocs 局部变更实体集合
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param <T>         文档实体类型
     * @return 批量操作结果模型（全量成功时返回）
     * @throws SearchBulkException 当任一文档更新失败时抛出
     */
    public <T> SearchBulkResult updateBatch(String index, Collection<T> partialDocs, boolean docAsUpsert) {
        return doBulkUpdate(index, partialDocs, docAsUpsert, false, null);
    }

    /**
     * 批量局部更新文档，支持 docAsUpsert 与写入刷新策略（严格快速失败模式）
     *
     * @param index         目标索引名（为 null 时自动解析实体注解）
     * @param partialDocs   局部变更实体集合
     * @param docAsUpsert   若文档不存在是否直接写入
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           文档实体类型
     * @return 批量操作结果模型
     */
    public <T> SearchBulkResult updateBatch(String index, Collection<T> partialDocs, boolean docAsUpsert, SearchRefreshPolicy refreshPolicy) {
        return doBulkUpdate(index, partialDocs, docAsUpsert, false, refreshPolicy);
    }

    /**
     * 批量局部更新文档（宽容模式）
     *
     * @param partialDocs 局部变更实体集合
     * @param <T>         文档实体类型
     * @return 批量操作结果模型
     */
    public <T> SearchBulkResult updateBatchLenient(Collection<T> partialDocs) {
        return updateBatchLenient(null, partialDocs, false);
    }

    /**
     * 批量局部更新文档，支持 docAsUpsert（宽容模式）
     *
     * @param index       目标索引名（为 null 时自动解析实体注解）
     * @param partialDocs 局部变更实体集合
     * @param docAsUpsert 若文档不存在是否直接写入
     * @param <T>         文档实体类型
     * @return 批量操作结果模型
     */
    public <T> SearchBulkResult updateBatchLenient(String index, Collection<T> partialDocs, boolean docAsUpsert) {
        return doBulkUpdate(index, partialDocs, docAsUpsert, true, null);
    }

    /**
     * 批量局部更新文档，支持 docAsUpsert 与写入刷新策略（宽容模式）
     *
     * @param index         目标索引名（为 null 时自动解析实体注解）
     * @param partialDocs   局部变更实体集合
     * @param docAsUpsert   若文档不存在是否直接写入
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @param <T>           文档实体类型
     * @return 批量操作结果模型
     */
    public <T> SearchBulkResult updateBatchLenient(String index, Collection<T> partialDocs, boolean docAsUpsert, SearchRefreshPolicy refreshPolicy) {
        return doBulkUpdate(index, partialDocs, docAsUpsert, true, refreshPolicy);
    }

    /**
     * 根据主键 ID 集合批量删除文档（严格快速失败模式）
     *
     * @param clazz 实体类类型（用于解析索引名）
     * @param ids   待删除的文档 ID 集合
     * @return 批量操作结果模型（全量成功时返回）
     * @throws SearchBulkException 当任一文档删除失败时抛出
     */
    public SearchBulkResult deleteBatch(Class<?> clazz, Collection<String> ids) {
        return deleteBatch(clazz, ids, null);
    }

    /**
     * 根据主键 ID 集合与分片路由键批量删除文档（严格快速失败模式）
     *
     * @param clazz   实体类类型（用于解析索引名）
     * @param ids     待删除的文档 ID 集合
     * @param routing 分片路由键（可为 null）
     * @return 批量操作结果模型（全量成功时返回）
     * @throws SearchBulkException 当任一文档删除失败时抛出
     */
    public SearchBulkResult deleteBatch(Class<?> clazz, Collection<String> ids, String routing) {
        return deleteBatch(clazz, ids, routing, null);
    }

    /**
     * 根据主键 ID 集合与分片路由键批量删除文档并指定写入刷新策略（严格快速失败模式）
     *
     * @param clazz         实体类类型（用于解析索引名）
     * @param ids           待删除的文档 ID 集合
     * @param routing       分片路由键（可为 null）
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 批量操作结果模型
     */
    public SearchBulkResult deleteBatch(Class<?> clazz, Collection<String> ids, String routing, SearchRefreshPolicy refreshPolicy) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return doBulkDelete(index, clazz, ids, routing, false, refreshPolicy);
    }

    /**
     * 根据主键 ID 集合批量从指定索引删除文档（严格快速失败模式）
     *
     * @param index 目标索引名
     * @param ids   待删除的文档 ID 集合
     * @return 批量操作结果模型（全量成功时返回）
     * @throws SearchBulkException 当任一文档删除失败时抛出
     */
    public SearchBulkResult deleteBatch(String index, Collection<String> ids) {
        return deleteBatch(index, ids, null);
    }

    /**
     * 根据主键 ID 集合与分片路由键批量从指定索引删除文档（严格快速失败模式）
     *
     * @param index   目标索引名
     * @param ids     待删除的文档 ID 集合
     * @param routing 分片路由键（可为 null）
     * @return 批量操作结果模型（全量成功时返回）
     * @throws SearchBulkException 当任一文档删除失败时抛出
     */
    public SearchBulkResult deleteBatch(String index, Collection<String> ids, String routing) {
        return deleteBatch(index, ids, routing, null);
    }

    /**
     * 根据主键 ID 集合与分片路由键批量从指定索引删除文档并指定写入刷新策略（严格快速失败模式）
     *
     * @param index         目标索引名
     * @param ids           待删除的文档 ID 集合
     * @param routing       分片路由键（可为 null）
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 批量操作结果模型
     */
    public SearchBulkResult deleteBatch(String index, Collection<String> ids, String routing, SearchRefreshPolicy refreshPolicy) {
        return doBulkDelete(index, null, ids, routing, false, refreshPolicy);
    }

    /**
     * 根据主键 ID 集合批量删除文档（宽容模式）
     *
     * @param clazz 实体类类型（用于解析索引名）
     * @param ids   待删除的文档 ID 集合
     * @return 批量操作结果模型
     */
    public SearchBulkResult deleteBatchLenient(Class<?> clazz, Collection<String> ids) {
        return deleteBatchLenient(clazz, ids, null);
    }

    /**
     * 根据主键 ID 集合与分片路由键批量删除文档（宽容模式）
     *
     * @param clazz   实体类类型（用于解析索引名）
     * @param ids     待删除的文档 ID 集合
     * @param routing 分片路由键（可为 null）
     * @return 批量操作结果模型
     */
    public SearchBulkResult deleteBatchLenient(Class<?> clazz, Collection<String> ids, String routing) {
        return deleteBatchLenient(clazz, ids, routing, null);
    }

    /**
     * 根据主键 ID 集合与分片路由键批量删除文档并指定写入刷新策略（宽容模式）
     *
     * @param clazz         实体类类型（用于解析索引名）
     * @param ids           待删除的文档 ID 集合
     * @param routing       分片路由键（可为 null）
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 批量操作结果模型
     */
    public SearchBulkResult deleteBatchLenient(Class<?> clazz, Collection<String> ids, String routing, SearchRefreshPolicy refreshPolicy) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return doBulkDelete(index, clazz, ids, routing, true, refreshPolicy);
    }

    /**
     * 根据主键 ID 集合批量从指定索引删除文档（宽容模式）
     *
     * @param index 目标索引名
     * @param ids   待删除的文档 ID 集合
     * @return 批量操作结果模型
     */
    public SearchBulkResult deleteBatchLenient(String index, Collection<String> ids) {
        return deleteBatchLenient(index, ids, null);
    }

    /**
     * 根据主键 ID 集合与分片路由键批量从指定索引删除文档（宽容模式）
     *
     * @param index   目标索引名
     * @param ids     待删除的文档 ID 集合
     * @param routing 分片路由键（可为 null）
     * @return 批量操作结果模型
     */
    public SearchBulkResult deleteBatchLenient(String index, Collection<String> ids, String routing) {
        return deleteBatchLenient(index, ids, routing, null);
    }

    /**
     * 根据主键 ID 集合与分片路由键批量从指定索引删除文档并指定写入刷新策略（宽容模式）
     *
     * @param index         目标索引名
     * @param ids           待删除的文档 ID 集合
     * @param routing       分片路由键（可为 null）
     * @param refreshPolicy 写入刷新策略（可为 null）
     * @return 批量操作结果模型
     */
    public SearchBulkResult deleteBatchLenient(String index, Collection<String> ids, String routing, SearchRefreshPolicy refreshPolicy) {
        return doBulkDelete(index, null, ids, routing, true, refreshPolicy);
    }

    private <T> SearchBulkResult doBulkIndex(String index, Collection<T> documents, boolean lenient, SearchRefreshPolicy refreshPolicy) {
        if (CollectionUtil.isEmpty(documents)) {
            return emptyBulkResult();
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        if (StringUtils.hasText(index)) {
            bulkBuilder.index(index);
        }
        if (refreshPolicy != null) {
            bulkBuilder.refresh(toEsRefresh(refreshPolicy));
        }

        int validItemCount = 0;
        ObjectMapper clientMapper = SearchTenantHelper.resolveObjectMapper(searchEngineClient);
        List<BulkOwnershipTarget> ownershipTargets = new ArrayList<>();
        for (T doc : documents) {
            if (doc == null) {
                continue;
            }
            Class<?> docClass = doc instanceof Map<?, ?> ? null : doc.getClass();
            String docIndex = StringUtils.hasText(index) ? index : SearchMetadataHelper.resolveIndexName(doc.getClass());
            String docId = SearchMetadataHelper.resolveDocumentId(doc);
            String routing = SearchMetadataHelper.resolveRouting(doc);
            Object payload = SearchTenantHelper.preparePayloadForWrite(doc, docClass, properties, clientMapper);
            if (StringUtils.hasText(docId)) {
                String resolvedTenantId = SearchTenantHelper.resolveActiveTenantId(properties, docClass);
                if (resolvedTenantId != null) {
                    ownershipTargets.add(new BulkOwnershipTarget(docIndex, docId, routing, resolvedTenantId));
                }
            }
            bulkBuilder.operations(op -> op.index(idx -> {
                idx.index(docIndex).document(payload);
                if (StringUtils.hasText(docId)) {
                    idx.id(docId);
                }
                if (StringUtils.hasText(routing)) {
                    idx.routing(routing);
                }
                return idx;
            }));
            validItemCount++;
        }

        if (validItemCount == 0) {
            return emptyBulkResult();
        }

        verifyBatchOwnershipIfStrict(ownershipTargets);
        return executeBulk(bulkBuilder.build(), validItemCount, lenient);
    }

    private <T> SearchBulkResult doBulkUpdate(String index, Collection<T> partialDocs, boolean docAsUpsert, boolean lenient, SearchRefreshPolicy refreshPolicy) {
        if (CollectionUtil.isEmpty(partialDocs)) {
            return emptyBulkResult();
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        if (StringUtils.hasText(index)) {
            bulkBuilder.index(index);
        }
        if (refreshPolicy != null) {
            bulkBuilder.refresh(toEsRefresh(refreshPolicy));
        }

        int validItemCount = 0;
        ObjectMapper clientMapper = SearchTenantHelper.resolveObjectMapper(searchEngineClient);
        List<BulkOwnershipTarget> ownershipTargets = new ArrayList<>();
        for (T doc : partialDocs) {
            if (doc == null) {
                continue;
            }
            Class<?> docClass = doc instanceof Map<?, ?> ? null : doc.getClass();
            String docIndex = StringUtils.hasText(index) ? index : SearchMetadataHelper.resolveIndexName(doc.getClass());
            String docId = SearchMetadataHelper.resolveDocumentId(doc);
            String routing = SearchMetadataHelper.resolveRouting(doc);
            if (!StringUtils.hasText(docId)) {
                throw new IllegalArgumentException("Cannot update document without an ID in batch update. Ensure @SearchId is configured on the entity.");
            }
            Object payload = SearchTenantHelper.preparePayloadForWrite(doc, docClass, properties, clientMapper);
            String resolvedTenantId = SearchTenantHelper.resolveActiveTenantId(properties, docClass);
            if (resolvedTenantId != null) {
                ownershipTargets.add(new BulkOwnershipTarget(docIndex, docId, routing, resolvedTenantId));
            }
            bulkBuilder.operations(op -> op.update(u -> {
                u.index(docIndex)
                        .id(docId)
                        .action(a -> a.doc(payload).docAsUpsert(docAsUpsert));
                if (StringUtils.hasText(routing)) {
                    u.routing(routing);
                }
                return u;
            }));
            validItemCount++;
        }

        if (validItemCount == 0) {
            return emptyBulkResult();
        }

        verifyBatchOwnershipIfStrict(ownershipTargets);
        return executeBulk(bulkBuilder.build(), validItemCount, lenient);
    }

    private SearchBulkResult doBulkDelete(String index,
                                          Class<?> entityClass,
                                          Collection<String> ids,
                                          String routing,
                                          boolean lenient,
                                          SearchRefreshPolicy refreshPolicy) {
        Objects.requireNonNull(index, "index must not be null");
        if (CollectionUtil.isEmpty(ids)) {
            return emptyBulkResult();
        }

        List<String> validIds = ids.stream().filter(StringUtils::hasText).toList();
        if (validIds.isEmpty()) {
            return emptyBulkResult();
        }

        String resolvedTenantId = SearchTenantHelper.resolveActiveTenantId(properties, entityClass);
        if (resolvedTenantId != null) {
            List<BulkOwnershipTarget> ownershipTargets = new ArrayList<>(validIds.size());
            for (String id : validIds) {
                ownershipTargets.add(new BulkOwnershipTarget(index, id, routing, resolvedTenantId));
            }
            verifyBatchOwnershipIfStrict(ownershipTargets);
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder().index(index);
        if (refreshPolicy != null) {
            bulkBuilder.refresh(toEsRefresh(refreshPolicy));
        }
        for (String id : validIds) {
            bulkBuilder.operations(op -> op.delete(d -> {
                d.index(index).id(id);
                if (StringUtils.hasText(routing)) {
                    d.routing(routing);
                }
                return d;
            }));
        }

        return executeBulk(bulkBuilder.build(), validIds.size(), lenient);
    }

    /**
     * STRICT 模式下批量写入/更新/删除前通过 {@code _mget} 仅拉取租户字段执行跨租户所有权预检
     *
     * @param targets 待预检的显式 ID 目标列表
     */
    public void verifyBatchOwnershipIfStrict(List<BulkOwnershipTarget> targets) {
        if (CollectionUtil.isEmpty(targets) || !SearchTenantHelper.isStrictMode(properties)) {
            return;
        }
        record BatchGroupKey(String tenantId, String index, String routing) {
        }
        Map<BatchGroupKey, Set<String>> groupedIds = new LinkedHashMap<>();
        for (BulkOwnershipTarget target : targets) {
            if (target == null
                    || !StringUtils.hasText(target.index())
                    || !StringUtils.hasText(target.id())
                    || !StringUtils.hasText(target.tenantId())) {
                continue;
            }
            String normalizedRouting = StringUtils.hasText(target.routing()) ? target.routing() : null;
            BatchGroupKey key = new BatchGroupKey(target.tenantId().trim(), target.index(), normalizedRouting);
            groupedIds.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(target.id());
        }
        if (groupedIds.isEmpty()) {
            return;
        }

        String tenantColumn = SearchTenantHelper.resolveTenantColumn(properties);
        for (Map.Entry<BatchGroupKey, Set<String>> entry : groupedIds.entrySet()) {
            BatchGroupKey key = entry.getKey();
            List<String> ids = new ArrayList<>(entry.getValue());
            try {
                MgetResponse<ObjectNode> ownershipResp = getClient().mget(b -> {
                    b.index(key.index()).ids(ids).sourceIncludes(tenantColumn);
                    if (StringUtils.hasText(key.routing())) {
                        b.routing(key.routing());
                    }
                    return b;
                }, ObjectNode.class);
                if (ownershipResp != null && CollectionUtil.isNotEmpty(ownershipResp.docs())) {
                    for (MultiGetResponseItem<ObjectNode> item : ownershipResp.docs()) {
                        if (item.isResult() && item.result().found()) {
                            GetResult<ObjectNode> foundItem = item.result();
                            if (!SearchTenantHelper.isOwnedByTenant(foundItem.source(), tenantColumn, key.tenantId())) {
                                throw new SearchException(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED,
                                        "Cross-tenant batch mutation blocked on index [" + key.index() + "] id ["
                                                + foundItem.id() + "] for tenant [" + key.tenantId() + "]");
                            }
                        }
                    }
                }
            } catch (SearchException se) {
                throw se;
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    log.debug("Index [{}] not found during strict batch ownership check; skipping batch group", key.index());
                    continue;
                }
                throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to verify batch tenant ownership on index [" + key.index() + "]: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 批量操作所有权预检目标描述符
     *
     * @param index    目标索引名
     * @param id       文档 ID
     * @param routing  分片路由键（可为 null）
     * @param tenantId 期望归属的租户标识
     */
    public record BulkOwnershipTarget(String index, String id, String routing, String tenantId) {
    }

    /**
     * 执行底层 Elasticsearch 批量提交请求
     *
     * @param bulkRequest Elasticsearch 官方 BulkRequest
     * @param lenient     是否启用宽容模式（true 时不抛出异常，返回包含失败明细的 SearchBulkResult；false 时任一失败快速抛出 SearchBulkException）
     * @return 批量操作结果模型
     */
    public SearchBulkResult executeBulk(BulkRequest bulkRequest, boolean lenient) {
        int totalItems = bulkRequest != null && bulkRequest.operations() != null ? bulkRequest.operations().size() : 0;
        return executeBulk(bulkRequest, totalItems, lenient);
    }

    /**
     * 执行底层 Elasticsearch 批量提交请求（支持指定预期操作数）
     *
     * @param bulkRequest Elasticsearch 官方 BulkRequest
     * @param totalItems  预期提交的操作项总数
     * @param lenient     是否启用宽容模式
     * @return 批量操作结果模型
     */
    public SearchBulkResult executeBulk(BulkRequest bulkRequest, int totalItems, boolean lenient) {
        String index = bulkRequest != null ? bulkRequest.index() : null;
        return executeObserved("bulk", index, null, null, null, true,
                () -> doExecuteBulk(bulkRequest, totalItems, lenient));
    }

    private SearchBulkResult doExecuteBulk(BulkRequest bulkRequest, int totalItems, boolean lenient) {
        // 1. 执行 Elasticsearch 批量提交
        try {
            BulkResponse response = getClient().bulk(bulkRequest);
            List<SearchBulkFailure> failures = new ArrayList<>();

            // 2. 结构化提取局部失败项明细
            if (response.errors() && response.items() != null) {
                for (BulkResponseItem item : response.items()) {
                    boolean isError = item.error() != null || (item.status() >= 400 && !"not_found".equalsIgnoreCase(item.result()));
                    if (isError) {
                        String errorReason = item.error() != null ? item.error().reason() : "HTTP status " + item.status();
                        String errorType = item.error() != null ? item.error().type() : null;
                        failures.add(SearchBulkFailure.builder()
                                .index(item.index())
                                .id(item.id())
                                .status(item.status())
                                .errorReason(errorReason)
                                .errorType(errorType)
                                .build());
                    }
                }
            }

            int failureCount = failures.size();
            int successCount = totalItems - failureCount;
            SearchBulkResult result = SearchBulkResult.builder()
                    .successCount(successCount)
                    .failureCount(failureCount)
                    .took(response.took())
                    .failures(Collections.unmodifiableList(failures))
                    .build();

            // 3. 严格模式下快速失败，抛出强类型受管异常
            if (!lenient && result.hasFailures()) {
                SearchBulkFailure first = failures.get(0);
                String summary = String.format("Bulk operation failed for %d item(s) out of %d. First error on id [%s] (status %d): %s",
                        failureCount, totalItems, first.getId(), first.getStatus(), first.getErrorReason());
                throw new SearchBulkException(summary, result.getFailures(), successCount, failureCount);
            }

            return result;
        } catch (SearchException se) {
            throw se;
        } catch (Exception e) {
            throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                    "Bulk operation execution failed: " + e.getMessage(), e);
        }
    }

    private static SearchBulkResult emptyBulkResult() {
        return SearchBulkResult.builder()
                .successCount(0)
                .failureCount(0)
                .took(0L)
                .failures(Collections.emptyList())
                .build();
    }

    // ==================== 6. 文档检索、计数与双轨分页 (Search, Count & SearchAfter) ====================

    /**
     * 统计指定实体映射索引中的文档总数
     *
     * @param clazz 实体类类型
     * @return 匹配的文档总数
     */
    public long count(Class<?> clazz) {
        return count(null, clazz);
    }

    /**
     * 统计指定实体映射索引中符合查询规格的文档总数
     * <p>
     * 对接原生 _count 轻量端点，免除相关度打分与文档排序开销；自动组装多租户隐式过滤。
     *
     * @param query 查询规格对象（若为 null 则统计全量文档）
     * @param clazz 实体类类型
     * @return 匹配的文档总数
     */
    public long count(SearchQuery query, Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = query != null && CollectionUtil.isNotEmpty(query.getIndices())
                ? String.join(",", query.getIndices())
                : SearchMetadataHelper.resolveIndexName(clazz);
        return count(index, query, clazz);
    }

    /**
     * 统计指定索引中符合查询规格的文档总数
     *
     * @param index 目标索引名
     * @param query 查询规格对象（若为 null 则统计全量文档）
     * @return 匹配的文档总数
     */
    public long count(String index, SearchQuery query) {
        return count(index, query, null);
    }

    /**
     * 统计指定索引中符合查询规格与实体映射规则的文档总数
     *
     * @param index 目标索引名
     * @param query 查询规格对象（若为 null 则统计全量文档）
     * @param clazz 实体类类型（用于多租户隐式过滤等元数据解析，可为 null）
     * @return 匹配的文档总数
     */
    public long count(String index, SearchQuery query, Class<?> clazz) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        return executeObserved("count", index, clazz, query != null ? query.getRouting() : null, query, false,
                () -> executeDoCount(index, query, clazz));
    }

    private long executeDoCount(String index, SearchQuery query, Class<?> clazz) {
        try {
            Query baseQuery = query != null ? query.getQuery() : null;
            Query effectiveQuery = wrapTenantFilterIfNecessary(baseQuery, clazz);

            CountResponse response = getClient().count(b -> {
                b.index(index);
                if (effectiveQuery != null) {
                    b.query(effectiveQuery);
                }
                if (query != null && StringUtils.hasText(query.getRouting())) {
                    b.routing(query.getRouting());
                }
                return b;
            });
            return response.count();
        } catch (Exception e) {
            if (SearchExceptionClassifier.isNotFound(e)) {
                log.debug("Index not found during count on index [{}]", index);
                return 0L;
            }
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_GET_ERROR),
                    "Failed to execute count on index [" + index + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 执行标准浅分页检索
     * <p>
     * 自动从实体类解析 @SearchIndex 索引名；当请求偏移量超过 max-result-window (默认 10,000) 时，
     * 自动触发安全熔断抛出 {@link SearchPaginationExceededException}。
     *
     * @param query 查询规格对象
     * @param clazz 实体类类型
     * @param <T>   实体类型
     * @return 分页结果模型 SearchPage
     */
    public <T> SearchPage<T> search(SearchQuery query, Class<T> clazz) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = CollectionUtil.isNotEmpty(query.getIndices())
                ? String.join(",", query.getIndices())
                : SearchMetadataHelper.resolveIndexName(clazz);
        return search(index, query, clazz);
    }

    /**
     * 在指定索引上执行标准浅分页检索
     *
     * @param index 目标索引名
     * @param query 查询规格对象
     * @param clazz 实体类类型
     * @param <T>   实体类型
     * @return 分页结果模型 SearchPage
     */
    public <T> SearchPage<T> search(String index, SearchQuery query, Class<T> clazz) {
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");

        // 浅分页安全阈值刚性熔断 (Fail-Fast)
        int requestedWindow = query.getFrom() + query.getSize();
        int maxWindow = properties.getPagination().getMaxResultWindow();
        if (requestedWindow > maxWindow) {
            throw new SearchPaginationExceededException(requestedWindow, maxWindow);
        }

        SearchResponse<T> response = doSearch(index, query, clazz, false, null, null);
        return buildSearchPage(response, query, clazz, true);
    }

    /**
     * 执行标准浅分页检索并安全投影至指定视图对象 (VO)，严守持久实体内存真明文不被高亮标签污染
     *
     * @param query            查询规格对象
     * @param indexEntityClass 索引映射实体类（用于解析 @SearchIndex、多租户隔离与反序列化）
     * @param resultVoClass    目标视图投影类（支持标注 @HighlightField 回填高亮片段）
     * @param <T>              索引实体类型
     * @param <V>              视图投影类型
     * @return 投影后的分页结果模型 SearchPage
     */
    public <T, V> SearchPage<V> search(SearchQuery query, Class<T> indexEntityClass, Class<V> resultVoClass) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(indexEntityClass, "indexEntityClass must not be null");
        Objects.requireNonNull(resultVoClass, "resultVoClass must not be null");
        String index = CollectionUtil.isNotEmpty(query.getIndices())
                ? String.join(",", query.getIndices())
                : SearchMetadataHelper.resolveIndexName(indexEntityClass);
        return search(index, query, indexEntityClass, resultVoClass);
    }

    /**
     * 在指定索引上执行标准浅分页检索并安全投影至指定视图对象 (VO)
     *
     * @param index            目标索引名
     * @param query            查询规格对象
     * @param indexEntityClass 索引映射实体类
     * @param resultVoClass    目标视图投影类
     * @param <T>              索引实体类型
     * @param <V>              视图投影类型
     * @return 投影后的分页结果模型 SearchPage
     */
    public <T, V> SearchPage<V> search(String index, SearchQuery query, Class<T> indexEntityClass, Class<V> resultVoClass) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(indexEntityClass, "indexEntityClass must not be null");
        Objects.requireNonNull(resultVoClass, "resultVoClass must not be null");

        int requestedWindow = query.getFrom() + query.getSize();
        int maxWindow = properties.getPagination().getMaxResultWindow();
        if (requestedWindow > maxWindow) {
            throw new SearchPaginationExceededException(requestedWindow, maxWindow);
        }

        SearchResponse<T> response = doSearch(index, query, indexEntityClass, false, null, null);
        SearchPage<T> entityPage = buildSearchPage(response, query, indexEntityClass, false);
        ObjectMapper clientMapper = SearchTenantHelper.resolveObjectMapper(searchEngineClient);
        return entityPage.map(entity -> clientMapper.convertValue(entity, resultVoClass));
    }

    // ==================== 6.2 批量异构检索 (_msearch) ====================

    /**
     * 单次 HTTP 往返批量执行多条异构检索查询
     * <p>
     * 自动从实体类解析默认索引并注入多租户隔离过滤条件，挂载 {@link SearchInterceptor} 与 {@link SearchMetrics}；
     * 若入参为空集合直接返回空列表（严禁返回 null）。
     *
     * @param queries 查询规格列表
     * @param clazz   实体类类型
     * @param <T>     实体类型
     * @return 与入参顺序一一对应的 {@link SearchPage} 结果列表
     */
    public <T> List<SearchPage<T>> msearch(List<SearchQuery> queries, Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        if (CollectionUtil.isEmpty(queries)) {
            return Collections.emptyList();
        }
        String defaultIndex = SearchMetadataHelper.resolveIndexName(clazz);
        return msearch(defaultIndex, queries, clazz);
    }

    /**
     * 在指定默认索引上单次 HTTP 往返批量执行多条异构检索查询
     *
     * @param index   默认目标索引名（单个子查询若通过 {@code indices()} 指定索引则优先使用子查询索引）
     * @param queries 查询规格列表
     * @param clazz   实体类类型
     * @param <T>     实体类型
     * @return 与入参顺序一一对应的 {@link SearchPage} 结果列表
     */
    public <T> List<SearchPage<T>> msearch(String index, List<SearchQuery> queries, Class<T> clazz) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(clazz, "clazz must not be null");
        if (CollectionUtil.isEmpty(queries)) {
            return Collections.emptyList();
        }
        validateMsearchPaginationWindows(queries);
        return executeObserved("msearch", index, clazz, null, null, false,
                () -> doMsearch(index, queries, clazz, true));
    }

    private void validateMsearchPaginationWindows(List<SearchQuery> queries) {
        int maxWindow = properties.getPagination().getMaxResultWindow();
        for (SearchQuery query : queries) {
            Objects.requireNonNull(query, "query item in msearch must not be null");
            int requestedWindow = query.getFrom() + query.getSize();
            if (requestedWindow > maxWindow) {
                throw new SearchPaginationExceededException(requestedWindow, maxWindow);
            }
        }
    }

    private <T> List<SearchPage<T>> doMsearch(String defaultIndex, List<SearchQuery> queries, Class<T> clazz, boolean populateHighlights) {
        try {
            List<RequestItem> requestItems = new ArrayList<>(queries.size());
            for (SearchQuery query : queries) {
                String targetIndex = CollectionUtil.isNotEmpty(query.getIndices())
                        ? String.join(",", query.getIndices())
                        : defaultIndex;
                PreparedSearchSpec spec = prepareSearchSpec(query, clazz, false, null, null, null);

                RequestItem item = RequestItem.of(ri -> ri
                        .header(h -> {
                            h.index(targetIndex);
                            if (StringUtils.hasText(query.getRouting())) {
                                h.routing(query.getRouting());
                            }
                            return h;
                        })
                        .body(b -> {
                            if (spec.finalQuery() != null) {
                                b.query(spec.finalQuery());
                            }
                            if (query.isTrackTotalHits()) {
                                b.trackTotalHits(tth -> tth.enabled(true));
                            }
                            b.from(spec.from());
                            b.size(spec.size());
                            if (CollectionUtil.isNotEmpty(spec.searchAfterValues())) {
                                b.searchAfter(spec.searchAfterValues());
                            }
                            if (CollectionUtil.isNotEmpty(spec.sorts())) {
                                b.sort(spec.sorts());
                            }
                            if (query.getHighlight() != null) {
                                b.highlight(query.getHighlight());
                            }
                            if (query.getCollapse() != null) {
                                b.collapse(query.getCollapse());
                            }
                            if (query.getAggregations() != null && !query.getAggregations().isEmpty()) {
                                b.aggregations(query.getAggregations());
                            }
                            if (CollectionUtil.isNotEmpty(query.getRescores())) {
                                b.rescore(query.getRescores());
                            }
                            if (CollectionUtil.isNotEmpty(query.getSuggesters())) {
                                b.suggest(s -> s.suggesters(query.getSuggesters()));
                            }
                            if (spec.sourceConfig() != null) {
                                b.source(spec.sourceConfig());
                            }
                            if (!spec.openSearch() && CollectionUtil.isNotEmpty(spec.effectiveKnn())) {
                                b.knn(spec.effectiveKnn());
                            }
                            return b;
                        })
                );
                requestItems.add(item);
            }

            MsearchResponse<T> response = getClient().msearch(b -> b.searches(requestItems), clazz);
            if (response == null || CollectionUtil.isEmpty(response.responses())) {
                return Collections.emptyList();
            }
            List<MultiSearchResponseItem<T>> items = response.responses();
            List<SearchPage<T>> pages = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                MultiSearchResponseItem<T> item = items.get(i);
                if (item.isFailure()) {
                    String reason = item.failure() != null && item.failure().error() != null
                            ? item.failure().error().reason()
                            : "unknown msearch item error";
                    throw new SearchException(SearchResultCode.SEARCH_DOCUMENT_GET_ERROR,
                            "msearch item [" + i + "] failed on index [" + defaultIndex + "]: " + reason);
                }
                if (item.isResult()) {
                    MultiSearchItem<T> resultItem = item.result();
                    SearchQuery sourceQuery = i < queries.size() ? queries.get(i) : queries.get(queries.size() - 1);
                    pages.add(buildSearchPage(resultItem, sourceQuery, clazz, populateHighlights));
                }
            }
            return pages;
        } catch (Exception e) {
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_GET_ERROR),
                    "Failed to execute msearch on index [" + defaultIndex + "]: " + e.getMessage(), e);
        }
    }

    // ==================== 6.3 混合检索融合 (Hybrid Search: BM25 + kNN) ====================

    /**
     * 执行默认 {@link SearchHybridMode#CLIENT_RRF} 模式的混合检索（BM25 全文词法 + kNN 稠密向量）
     *
     * @param textQuery     全文词法查询（可为 null，退化为 match_all）
     * @param vectorField   稠密向量字段名
     * @param vector        查询向量
     * @param k             向量召回 TopK
     * @param numCandidates 向量分片候选数
     * @param clazz         实体类类型
     * @param <T>           实体类型
     * @return 融合重排后的分页结果模型
     */
    public <T> SearchPage<T> hybridSearch(SearchQuery textQuery,
                                          String vectorField,
                                          List<Float> vector,
                                          int k,
                                          int numCandidates,
                                          Class<T> clazz) {
        return hybridSearch(textQuery, vectorField, vector, k, numCandidates, HybridSearchOptions.defaults(), clazz);
    }

    /**
     * 执行可配置融合策略的混合检索（自动从实体类解析索引名）
     *
     * @param textQuery     全文词法查询（可为 null，退化为 match_all）
     * @param vectorField   稠密向量字段名
     * @param vector        查询向量
     * @param k             向量召回 TopK
     * @param numCandidates 向量分片候选数
     * @param options       混合融合配置（为 null 时使用默认 {@code CLIENT_RRF}）
     * @param clazz         实体类类型
     * @param <T>           实体类型
     * @return 融合重排后的分页结果模型
     */
    public <T> SearchPage<T> hybridSearch(SearchQuery textQuery,
                                          String vectorField,
                                          List<Float> vector,
                                          int k,
                                          int numCandidates,
                                          HybridSearchOptions options,
                                          Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = textQuery != null && CollectionUtil.isNotEmpty(textQuery.getIndices())
                ? String.join(",", textQuery.getIndices())
                : SearchMetadataHelper.resolveIndexName(clazz);
        return hybridSearch(index, textQuery, vectorField, vector, k, numCandidates, options, clazz);
    }

    /**
     * 在指定索引上执行默认 {@link SearchHybridMode#CLIENT_RRF} 模式的混合检索
     *
     * @param index         目标索引名
     * @param textQuery     全文词法查询（可为 null，退化为 match_all）
     * @param vectorField   稠密向量字段名
     * @param vector        查询向量
     * @param k             向量召回 TopK
     * @param numCandidates 向量分片候选数
     * @param clazz         实体类类型
     * @param <T>           实体类型
     * @return 融合重排后的分页结果模型
     */
    public <T> SearchPage<T> hybridSearch(String index,
                                          SearchQuery textQuery,
                                          String vectorField,
                                          List<Float> vector,
                                          int k,
                                          int numCandidates,
                                          Class<T> clazz) {
        return hybridSearch(index, textQuery, vectorField, vector, k, numCandidates, HybridSearchOptions.defaults(), clazz);
    }

    /**
     * 在指定索引上执行混合检索（支持 {@code CLIENT_RRF} / {@code CLIENT_WEIGHTED} / {@code NATIVE_RRF}）
     *
     * @param index         目标索引名
     * @param textQuery     全文词法查询（可为 null，退化为 match_all）
     * @param vectorField   稠密向量字段名
     * @param vector        查询向量
     * @param k             向量召回 TopK
     * @param numCandidates 向量分片候选数
     * @param options       混合融合配置（为 null 时使用默认 {@code CLIENT_RRF}）
     * @param clazz         实体类类型
     * @param <T>           实体类型
     * @return 融合重排后的分页结果模型
     */
    public <T> SearchPage<T> hybridSearch(String index,
                                          SearchQuery textQuery,
                                          String vectorField,
                                          List<Float> vector,
                                          int k,
                                          int numCandidates,
                                          HybridSearchOptions options,
                                          Class<T> clazz) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Objects.requireNonNull(clazz, "clazz must not be null");
        if (!StringUtils.hasText(vectorField)) {
            throw new IllegalArgumentException("vectorField must not be blank");
        }
        if (CollectionUtil.isEmpty(vector)) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        if (numCandidates <= 0) {
            throw new IllegalArgumentException("numCandidates must be positive: " + numCandidates);
        }

        HybridSearchOptions resolvedOptions = options != null ? options : HybridSearchOptions.defaults();
        SearchHybridMode mode = resolvedOptions.getMode() != null ? resolvedOptions.getMode() : SearchHybridMode.CLIENT_RRF;
        SearchQuery baseTextQuery = textQuery != null ? textQuery : SearchQueryBuilder.create().matchAll().build();
        int topK = resolvedOptions.getTopK() > 0
                ? resolvedOptions.getTopK()
                : (baseTextQuery.getSize() > 0 ? baseTextQuery.getSize() : 10);
        int windowSize = resolvedOptions.getWindowSize() != null && resolvedOptions.getWindowSize() > 0
                ? resolvedOptions.getWindowSize()
                : Math.max(topK, k);
        int maxWindow = properties.getPagination().getMaxResultWindow();
        if (topK > maxWindow) {
            throw new SearchPaginationExceededException(topK, maxWindow);
        }
        if (windowSize > maxWindow) {
            throw new SearchPaginationExceededException(windowSize, maxWindow);
        }

        HybridVectorSpec vectorSpec = new HybridVectorSpec(vectorField, vector, k, numCandidates);
        return executeObserved("hybridSearch", index, clazz, baseTextQuery.getRouting(), baseTextQuery, false, () -> {
            if (mode == SearchHybridMode.NATIVE_RRF) {
                return executeNativeRrfHybridSearch(index, baseTextQuery, vectorSpec,
                        resolvedOptions, topK, windowSize, clazz);
            }
            return executeClientHybridSearch(index, baseTextQuery, vectorSpec,
                    resolvedOptions, mode, topK, windowSize, clazz);
        });
    }

    private record HybridVectorSpec(String vectorField, List<Float> vector, int k, int numCandidates) {
    }

    private <T> SearchPage<T> executeNativeRrfHybridSearch(String index,
                                                           SearchQuery baseTextQuery,
                                                           HybridVectorSpec vectorSpec,
                                                           HybridSearchOptions options,
                                                           int topK,
                                                           int windowSize,
                                                           Class<T> clazz) {
        if (SearchEngineDialect.isOpenSearch(searchEngineClient.getEngineType())) {
            throw new SearchException(SearchResultCode.SEARCH_BAD_REQUEST,
                    "SearchHybridMode.NATIVE_RRF requires Elasticsearch 8.8+ rank.rrf and is unsupported on OpenSearch; use CLIENT_RRF instead.");
        }
        if (options.getRrfRankConstant() <= 0) {
            throw new IllegalArgumentException("rrfRankConstant must be positive: " + options.getRrfRankConstant());
        }
        try {
            Query scopedQuery = wrapTenantFilterIfNecessary(baseTextQuery.getQuery(), clazz);
            SearchQuery knnBranch = SearchQueryBuilder.create()
                    .knn(vectorSpec.vectorField(), vectorSpec.vector(),
                            Math.max(vectorSpec.k(), windowSize),
                            Math.max(vectorSpec.numCandidates(), Math.max(vectorSpec.k(), windowSize)),
                            options.getKnnFilter())
                    .build();
            List<KnnSearch> scopedKnn = enrichKnnWithTenantFilter(knnBranch.getKnnSearches(), clazz);
            SourceConfig sourceConfig = baseTextQuery.toSourceConfig();

            SearchResponse<T> response = getClient().search(b -> {
                b.index(index).from(0).size(topK);
                if (scopedQuery != null) {
                    b.query(scopedQuery);
                }
                if (StringUtils.hasText(baseTextQuery.getRouting())) {
                    b.routing(baseTextQuery.getRouting());
                }
                if (baseTextQuery.isTrackTotalHits()) {
                    b.trackTotalHits(tth -> tth.enabled(true));
                }
                if (baseTextQuery.getHighlight() != null) {
                    b.highlight(baseTextQuery.getHighlight());
                }
                if (sourceConfig != null) {
                    b.source(sourceConfig);
                }
                if (CollectionUtil.isNotEmpty(scopedKnn)) {
                    b.knn(scopedKnn);
                }
                b.rank(r -> r.rrf(rrf -> rrf
                        .rankConstant((long) options.getRrfRankConstant())
                        .windowSize((long) windowSize)));
                return b;
            }, clazz);
            return buildSearchPage(response, baseTextQuery.deriveBranch(baseTextQuery.getQuery(), scopedKnn, 0, topK, true), clazz, true);
        } catch (Exception e) {
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_GET_ERROR),
                    "Failed to execute native RRF hybrid search on index [" + index + "]: " + e.getMessage(), e);
        }
    }

    private <T> SearchPage<T> executeClientHybridSearch(String index,
                                                        SearchQuery baseTextQuery,
                                                        HybridVectorSpec vectorSpec,
                                                        HybridSearchOptions options,
                                                        SearchHybridMode mode,
                                                        int topK,
                                                        int windowSize,
                                                        Class<T> clazz) {
        if (mode == SearchHybridMode.CLIENT_RRF && options.getRrfRankConstant() <= 0) {
            throw new IllegalArgumentException("rrfRankConstant must be positive: " + options.getRrfRankConstant());
        }
        if (mode == SearchHybridMode.CLIENT_WEIGHTED
                && (options.getTextWeight() < 0 || options.getVectorWeight() < 0
                || (options.getTextWeight() + options.getVectorWeight()) <= 0)) {
            throw new IllegalArgumentException("textWeight and vectorWeight must be non-negative and their sum must be positive");
        }

        SearchQuery bm25Branch = baseTextQuery.deriveBranch(
                baseTextQuery.getQuery() != null ? baseTextQuery.getQuery() : Query.of(q -> q.matchAll(m -> m)),
                Collections.emptyList(),
                0,
                windowSize,
                true
        );
        SearchQuery knnSpec = SearchQueryBuilder.create()
                .knn(vectorSpec.vectorField(), vectorSpec.vector(),
                        Math.max(vectorSpec.k(), windowSize),
                        Math.max(vectorSpec.numCandidates(), Math.max(vectorSpec.k(), windowSize)),
                        options.getKnnFilter())
                .build();
        SearchQuery knnBranch = baseTextQuery.deriveBranch(
                null,
                knnSpec.getKnnSearches(),
                0,
                windowSize,
                false
        );

        List<SearchPage<T>> branchPages = doMsearch(index, List.of(bm25Branch, knnBranch), clazz, true);
        List<SearchHit<T>> textHits = !branchPages.isEmpty() ? branchPages.get(0).getHits() : Collections.emptyList();
        List<SearchHit<T>> vectorHits = branchPages.size() > 1 ? branchPages.get(1).getHits() : Collections.emptyList();
        Map<String, Aggregate> aggregations = !branchPages.isEmpty() ? branchPages.get(0).getAggregations() : Collections.emptyMap();

        HybridFusionResult<T> fusionResult = fuseHybridHits(textHits, vectorHits, options, mode, topK);
        return SearchPage.<T>builder()
                .hits(fusionResult.hits())
                .total(fusionResult.totalMerged())
                .pageNum(1)
                .pageSize(topK)
                .aggregations(aggregations != null ? aggregations : Collections.emptyMap())
                .build();
    }

    private record HybridFusionResult<T>(List<SearchHit<T>> hits, long totalMerged) {
    }

    private static <T> HybridFusionResult<T> fuseHybridHits(List<SearchHit<T>> textHits,
                                                            List<SearchHit<T>> vectorHits,
                                                            HybridSearchOptions options,
                                                            SearchHybridMode mode,
                                                            int topK) {
        Map<String, SearchHit<T>> mergedMap = new LinkedHashMap<>();
        Map<String, Double> scoreMap = new LinkedHashMap<>();

        if (mode == SearchHybridMode.CLIENT_WEIGHTED) {
            double maxTextScore = maxPositiveScore(textHits);
            double maxVectorScore = maxPositiveScore(vectorHits);
            accumulateWeightedScores(textHits, maxTextScore, options.getTextWeight(), mergedMap, scoreMap, "text");
            accumulateWeightedScores(vectorHits, maxVectorScore, options.getVectorWeight(), mergedMap, scoreMap, "vec");
        } else {
            int rankConstant = options.getRrfRankConstant();
            accumulateRrfScores(textHits, rankConstant, mergedMap, scoreMap, "text");
            accumulateRrfScores(vectorHits, rankConstant, mergedMap, scoreMap, "vec");
        }

        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(scoreMap.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int limit = Math.min(topK, sortedEntries.size());
        List<SearchHit<T>> fused = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Double> entry = sortedEntries.get(i);
            SearchHit<T> candidate = mergedMap.get(entry.getKey());
            fused.add(candidate.toBuilder().score(entry.getValue()).build());
        }
        return new HybridFusionResult<>(fused, sortedEntries.size());
    }

    private static <T> void accumulateRrfScores(List<SearchHit<T>> hits,
                                                int rankConstant,
                                                Map<String, SearchHit<T>> mergedMap,
                                                Map<String, Double> scoreMap,
                                                String branchTag) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            SearchHit<T> hit = hits.get(i);
            String key = resolveHybridDocKey(hit, branchTag, i);
            double rrfContribution = 1.0d / (rankConstant + (i + 1));
            scoreMap.merge(key, rrfContribution, Double::sum);
            mergeCandidateHit(mergedMap, key, hit);
        }
    }

    private static <T> void accumulateWeightedScores(List<SearchHit<T>> hits,
                                                     double maxScore,
                                                     double weight,
                                                     Map<String, SearchHit<T>> mergedMap,
                                                     Map<String, Double> scoreMap,
                                                     String branchTag) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            SearchHit<T> hit = hits.get(i);
            String key = resolveHybridDocKey(hit, branchTag, i);
            double raw = hit.getScore() != null && hit.getScore() > 0d ? hit.getScore() : 0d;
            double normalized = maxScore > 0d ? raw / maxScore : 0d;
            scoreMap.merge(key, weight * normalized, Double::sum);
            mergeCandidateHit(mergedMap, key, hit);
        }
    }

    private static <T> double maxPositiveScore(List<SearchHit<T>> hits) {
        double max = 0d;
        if (hits != null) {
            for (SearchHit<T> hit : hits) {
                if (hit.getScore() != null && hit.getScore() > max) {
                    max = hit.getScore();
                }
            }
        }
        return max > 0d ? max : 1.0d;
    }

    private static <T> void mergeCandidateHit(Map<String, SearchHit<T>> mergedMap, String key, SearchHit<T> incoming) {
        SearchHit<T> existing = mergedMap.get(key);
        if (existing == null) {
            mergedMap.put(key, incoming);
            return;
        }
        // 保留既有 BM25 高亮片段，若既有无高亮而新候选带高亮则补齐
        if ((existing.getHighlightFields() == null || existing.getHighlightFields().isEmpty())
                && incoming.getHighlightFields() != null && !incoming.getHighlightFields().isEmpty()) {
            mergedMap.put(key, existing.toBuilder()
                    .content(existing.getContent() != null ? existing.getContent() : incoming.getContent())
                    .highlightFields(incoming.getHighlightFields())
                    .build());
        }
    }

    private static <T> String resolveHybridDocKey(SearchHit<T> hit, String branchTag, int idx) {
        if (StringUtils.hasText(hit.getId())) {
            return StringUtils.hasText(hit.getIndex()) ? hit.getIndex() + "::" + hit.getId() : hit.getId();
        }
        return "__anon_" + branchTag + "_" + idx;
    }

    /**
     * 执行深度游标滚动分页检索（首页检索）
     *
     * @param query 查询规格对象
     * @param size  每页大小
     * @param clazz 实体类类型
     * @param <T>   实体类型
     * @return 深度滚动结果模型 SearchScrollResult
     */
    public <T> SearchScrollResult<T> searchAfter(SearchQuery query, int size, Class<T> clazz) {
        return searchAfter(query, Collections.emptyList(), size, clazz);
    }

    /**
     * 在指定索引上执行深度游标滚动分页检索（首页检索）
     *
     * @param index 目标索引名
     * @param query 查询规格对象
     * @param size  每页大小
     * @param clazz 实体类类型
     * @param <T>   实体类型
     * @return 深度滚动结果模型 SearchScrollResult
     */
    public <T> SearchScrollResult<T> searchAfter(String index, SearchQuery query, int size, Class<T> clazz) {
        return searchAfter(index, query, Collections.emptyList(), size, clazz);
    }

    /**
     * 执行深度游标滚动分页检索 (SearchAfter)
     * <p>
     * 自动从实体类解析 @SearchIndex 索引名；通过透传上一批末尾的 sortValues 实现无内存膨胀的深翻页。
     *
     * @param query      查询规格对象
     * @param sortValues 上一批最后一条记录的排序凭据值列表（首页为 null 或空集合）
     * @param size       每页大小
     * @param clazz      实体类类型
     * @param <T>        实体类型
     * @return 深度滚动结果模型 SearchScrollResult
     */
    public <T> SearchScrollResult<T> searchAfter(SearchQuery query, List<String> sortValues, int size, Class<T> clazz) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = CollectionUtil.isNotEmpty(query.getIndices())
                ? String.join(",", query.getIndices())
                : SearchMetadataHelper.resolveIndexName(clazz);
        return searchAfter(index, query, sortValues, size, clazz);
    }

    /**
     * 在指定索引上执行深度游标滚动分页检索 (SearchAfter)
     *
     * @param index      目标索引名
     * @param query      查询规格对象
     * @param sortValues 上一批最后一条记录的排序凭据值列表（首页为 null 或空集合）
     * @param size       每页大小
     * @param clazz      实体类类型
     * @param <T>        实体类型
     * @return 深度滚动结果模型 SearchScrollResult
     */
    public <T> SearchScrollResult<T> searchAfter(String index, SearchQuery query, List<String> sortValues, int size, Class<T> clazz) {
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");

        int pageSize = size > 0 ? size : query.getSize();
        validateSearchAfterSize(pageSize);
        SearchResponse<T> response = doSearch(index, query, clazz, true, sortValues, pageSize);
        return buildSearchScrollResult(response, pageSize, clazz);
    }

    private void validateSearchAfterSize(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("searchAfter size must be positive: " + pageSize);
        }
        int maxSearchAfterSize = properties.getPagination().getMaxSearchAfterSize();
        if (maxSearchAfterSize > 0 && pageSize > maxSearchAfterSize) {
            throw SearchPaginationExceededException.forSearchAfter(pageSize, maxSearchAfterSize);
        }
    }

    /**
     * 执行深度游标滚动分页检索 (SearchAfter，支持 Object[] 数组形式的排序凭据)
     *
     * @param query      查询规格对象
     * @param sortValues 上一批最后一条记录的排序凭据数组（首页为 null 或空数组）
     * @param size       每页大小
     * @param clazz      实体类类型
     * @param <T>        实体类型
     * @return 深度滚动结果模型 SearchScrollResult
     */
    public <T> SearchScrollResult<T> searchAfter(SearchQuery query, Object[] sortValues, int size, Class<T> clazz) {
        return searchAfter(query, toSortValuesList(sortValues), size, clazz);
    }

    /**
     * 在指定索引上执行深度游标滚动分页检索 (SearchAfter，支持 Object[] 数组形式的排序凭据)
     *
     * @param index      目标索引名
     * @param query      查询规格对象
     * @param sortValues 上一批最后一条记录的排序凭据数组（首页为 null 或空数组）
     * @param size       每页大小
     * @param clazz      实体类类型
     * @param <T>        实体类型
     * @return 深度滚动结果模型 SearchScrollResult
     */
    public <T> SearchScrollResult<T> searchAfter(String index, SearchQuery query, Object[] sortValues, int size, Class<T> clazz) {
        return searchAfter(index, query, toSortValuesList(sortValues), size, clazz);
    }

    // ==================== 6.1 Point-In-Time (PIT) 一致性快照深分页 ====================

    /**
     * 为实体映射索引开启一致性检索快照点 (Point-In-Time)，默认保活 1 分钟
     *
     * @param clazz 实体类类型
     * @return 不可变 {@link SearchPointInTime} 句柄
     */
    public SearchPointInTime openPointInTime(Class<?> clazz) {
        return openPointInTime(clazz, Duration.ofMinutes(1));
    }

    /**
     * 为实体映射索引开启一致性检索快照点 (Point-In-Time)
     *
     * @param clazz     实体类类型
     * @param keepAlive 快照保活时长（正数）
     * @return 不可变 {@link SearchPointInTime} 句柄
     */
    public SearchPointInTime openPointInTime(Class<?> clazz, Duration keepAlive) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return openPointInTime(index, keepAlive);
    }

    /**
     * 为指定索引开启一致性检索快照点 (Point-In-Time)，默认保活 1 分钟
     *
     * @param index 目标索引名或别名
     * @return 不可变 {@link SearchPointInTime} 句柄
     */
    public SearchPointInTime openPointInTime(String index) {
        return openPointInTime(index, Duration.ofMinutes(1));
    }

    /**
     * 为指定索引开启一致性检索快照点 (Point-In-Time)
     *
     * @param index     目标索引名或别名
     * @param keepAlive 快照保活时长（正数）
     * @return 不可变 {@link SearchPointInTime} 句柄
     */
    public SearchPointInTime openPointInTime(String index, Duration keepAlive) {
        if (!StringUtils.hasText(index)) {
            throw new IllegalArgumentException("Index must not be blank");
        }
        Duration resolvedKeepAlive = keepAlive != null ? keepAlive : Duration.ofMinutes(1);
        SearchEngineDialect.formatKeepAlive(resolvedKeepAlive);
        return executeObserved("openPointInTime", index, null, null, null, false, () -> {
            try {
                return SearchEngineDialect.openPointInTime(searchEngineClient, index, resolvedKeepAlive);
            } catch (Exception e) {
                if (e instanceof SearchException se) {
                    throw se;
                }
                throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to open Point-In-Time on index [" + index + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 显式关闭指定的 Point-In-Time 快照句柄
     *
     * @param pit PIT 快照句柄
     * @return true 表示释放成功；若快照已过期或不存在返回 false
     */
    public boolean closePointInTime(SearchPointInTime pit) {
        Objects.requireNonNull(pit, "pit must not be null");
        return closePointInTime(pit.pitId());
    }

    /**
     * 显式关闭指定 ID 的 Point-In-Time 快照
     *
     * @param pitId PIT 快照唯一标识
     * @return true 表示释放成功；若快照已过期或不存在返回 false
     */
    public boolean closePointInTime(String pitId) {
        if (!StringUtils.hasText(pitId)) {
            throw new IllegalArgumentException("pitId must not be blank");
        }
        return executeObserved("closePointInTime", null, null, null, null, false, () -> {
            try {
                return SearchEngineDialect.closePointInTime(searchEngineClient, pitId);
            } catch (Exception e) {
                if (SearchExceptionClassifier.isNotFound(e)) {
                    return false;
                }
                if (e instanceof SearchException se) {
                    throw se;
                }
                throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_OPERATION_ERROR),
                        "Failed to close Point-In-Time [" + pitId + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 基于一致性快照点执行深度游标滚动分页检索（首页）
     *
     * @param pit   PIT 快照句柄
     * @param query 查询规格对象
     * @param size  每页大小
     * @param clazz 实体类类型
     * @param <T>   实体类型
     * @return 深度滚动结果模型
     */
    public <T> SearchScrollResult<T> searchAfterWithPit(SearchPointInTime pit, SearchQuery query, int size, Class<T> clazz) {
        return searchAfterWithPit(pit, query, Collections.emptyList(), size, clazz);
    }

    /**
     * 基于一致性快照点执行深度游标滚动分页检索（支持 {@code List<String>} 排序凭据）
     *
     * @param pit        PIT 快照句柄
     * @param query      查询规格对象
     * @param sortValues 上一批末尾排序凭据
     * @param size       每页大小
     * @param clazz      实体类类型
     * @param <T>        实体类型
     * @return 深度滚动结果模型
     */
    public <T> SearchScrollResult<T> searchAfterWithPit(SearchPointInTime pit,
                                                        SearchQuery query,
                                                        List<String> sortValues,
                                                        int size,
                                                        Class<T> clazz) {
        Objects.requireNonNull(pit, "pit must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");

        int pageSize = size > 0 ? size : query.getSize();
        validateSearchAfterSize(pageSize);
        SearchResponse<T> response = doSearch(pit.index(), query, clazz, true, sortValues, pageSize, pit);
        return buildSearchScrollResult(response, pageSize, clazz);
    }

    /**
     * 基于一致性快照点执行深度游标滚动分页检索（支持 {@code Object[]} 排序凭据）
     *
     * @param pit        PIT 快照句柄
     * @param query      查询规格对象
     * @param sortValues 上一批末尾排序凭据数组
     * @param size       每页大小
     * @param clazz      实体类类型
     * @param <T>        实体类型
     * @return 深度滚动结果模型
     */
    public <T> SearchScrollResult<T> searchAfterWithPit(SearchPointInTime pit,
                                                        SearchQuery query,
                                                        Object[] sortValues,
                                                        int size,
                                                        Class<T> clazz) {
        return searchAfterWithPit(pit, query, toSortValuesList(sortValues), size, clazz);
    }

    /**
     * 在受管 Point-In-Time 生命周期模板中执行分页遍历，并在 {@code finally} 块确定性释放服务端快照
     *
     * @param clazz     实体类类型
     * @param keepAlive 快照保活时长
     * @param action    基于 PIT 句柄的业务逻辑闭包
     * @param <R>       返回结果泛型
     * @return 闭包返回值
     */
    public <R> R executeWithPointInTime(Class<?> clazz, Duration keepAlive, Function<SearchPointInTime, R> action) {
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return executeWithPointInTime(index, keepAlive, action);
    }

    /**
     * 在受管 Point-In-Time 生命周期模板中执行分页遍历，并在 {@code finally} 块确定性释放服务端快照
     *
     * @param index     目标索引名或别名
     * @param keepAlive 快照保活时长
     * @param action    基于 PIT 句柄的业务逻辑闭包
     * @param <R>       返回结果泛型
     * @return 闭包返回值
     */
    public <R> R executeWithPointInTime(String index, Duration keepAlive, Function<SearchPointInTime, R> action) {
        Objects.requireNonNull(action, "action must not be null");
        SearchPointInTime pit = openPointInTime(index, keepAlive);
        try {
            return action.apply(pit);
        } finally {
            try {
                closePointInTime(pit.pitId());
            } catch (Exception closeEx) {
                log.warn("Failed to close PIT [{}] on index [{}]: {}", pit.pitId(), index, closeEx.getMessage(), closeEx);
            }
        }
    }

    private static List<String> toSortValuesList(Object[] sortValues) {
        if (sortValues == null || sortValues.length == 0) {
            return Collections.emptyList();
        }
        return Arrays.stream(sortValues).filter(Objects::nonNull).map(Object::toString).toList();
    }

    private <T> SearchResponse<T> doSearch(String index, SearchQuery query, Class<T> clazz,
                                           boolean isSearchAfter, List<String> sortValues, Integer overrideSize) {
        return doSearch(index, query, clazz, isSearchAfter, sortValues, overrideSize, null);
    }

    private <T> SearchResponse<T> doSearch(String index, SearchQuery query, Class<T> clazz,
                                           boolean isSearchAfter, List<String> sortValues, Integer overrideSize,
                                           SearchPointInTime pit) {
        String operation = pit != null ? "searchAfterWithPit" : (isSearchAfter ? "searchAfter" : "search");
        return executeObserved(operation, index, clazz, query != null ? query.getRouting() : null, query, false,
                () -> executeDoSearch(index, query, clazz, isSearchAfter, sortValues, overrideSize, pit));
    }

    private record PreparedSearchSpec(
            boolean openSearch,
            Query finalQuery,
            List<KnnSearch> effectiveKnn,
            SourceConfig sourceConfig,
            int from,
            int size,
            List<FieldValue> searchAfterValues,
            List<SortOptions> sorts
    ) {
    }

    private PreparedSearchSpec prepareSearchSpec(SearchQuery query,
                                                 Class<?> clazz,
                                                 boolean isSearchAfter,
                                                 List<String> sortValues,
                                                 Integer overrideSize,
                                                 SearchPointInTime pit) {
        // 组装多租户隐式过滤（含 kNN 预过滤租户隔离）
        Query baseQuery = wrapTenantFilterIfNecessary(query.getQuery(), clazz);
        List<KnnSearch> effectiveKnn = enrichKnnWithTenantFilter(query.getKnnSearches(), clazz);
        boolean isOpenSearch = SearchEngineDialect.isOpenSearch(searchEngineClient.getEngineType());
        Query finalQuery = SearchEngineDialect.adaptKnnQuery(
                searchEngineClient.getEngineType(), baseQuery, effectiveKnn);
        SourceConfig sourceConfig = query.toSourceConfig();

        int from = isSearchAfter ? 0 : query.getFrom();
        int size = isSearchAfter
                ? (overrideSize != null && overrideSize > 0 ? overrideSize : query.getSize())
                : query.getSize();

        List<FieldValue> searchAfterValues = null;
        if (isSearchAfter) {
            if (sortValues != null && !sortValues.isEmpty()) {
                searchAfterValues = sortValues.stream().map(FieldValue::of).toList();
            }
        } else if (CollectionUtil.isNotEmpty(query.getSearchAfter())) {
            searchAfterValues = query.getSearchAfter().stream().map(FieldValue::of).toList();
        }

        List<SortOptions> sorts = null;
        if (CollectionUtil.isNotEmpty(query.getSorts())) {
            sorts = query.getSorts();
        } else if (isSearchAfter) {
            // searchAfter 强制要求至少有一个确定的排序规则（PIT 默认使用 _shard_doc，普通 searchAfter 保底 _id）
            String fallbackSortField = pit != null ? "_shard_doc" : "_id";
            sorts = List.of(SortOptions.of(s -> s.field(f -> f.field(fallbackSortField).order(SortOrder.Asc))));
        }

        return new PreparedSearchSpec(isOpenSearch, finalQuery, effectiveKnn, sourceConfig,
                from, size, searchAfterValues, sorts);
    }

    private <T> SearchResponse<T> executeDoSearch(String index, SearchQuery query, Class<T> clazz,
                                                  boolean isSearchAfter, List<String> sortValues, Integer overrideSize,
                                                  SearchPointInTime pit) {
        try {
            PreparedSearchSpec spec = prepareSearchSpec(query, clazz, isSearchAfter, sortValues, overrideSize, pit);

            SearchResponse<T> response = getClient().search(b -> {
                if (pit != null) {
                    b.pit(p -> {
                        p.id(pit.pitId());
                        if (pit.keepAlive() != null) {
                            p.keepAlive(t -> t.time(SearchEngineDialect.formatKeepAlive(pit.keepAlive())));
                        }
                        return p;
                    });
                } else {
                    b.index(index);
                }
                if (spec.finalQuery() != null) {
                    b.query(spec.finalQuery());
                }
                if (pit == null && StringUtils.hasText(query.getRouting())) {
                    b.routing(query.getRouting());
                }
                if (query.isTrackTotalHits()) {
                    b.trackTotalHits(tth -> tth.enabled(true));
                }

                // 分页与深游标配置
                b.from(spec.from());
                b.size(spec.size());
                if (CollectionUtil.isNotEmpty(spec.searchAfterValues())) {
                    b.searchAfter(spec.searchAfterValues());
                }

                // 排序规则
                if (CollectionUtil.isNotEmpty(spec.sorts())) {
                    b.sort(spec.sorts());
                }

                // 高亮配置
                if (query.getHighlight() != null) {
                    b.highlight(query.getHighlight());
                }

                // 字段折叠去重 (Field Collapsing)
                if (query.getCollapse() != null) {
                    b.collapse(query.getCollapse());
                }

                // _source 裁剪配置
                if (spec.sourceConfig() != null) {
                    b.source(spec.sourceConfig());
                }

                // 聚合配置
                if (query.getAggregations() != null && !query.getAggregations().isEmpty()) {
                    b.aggregations(query.getAggregations());
                }

                // 二阶段精排 Rescore 配置
                if (CollectionUtil.isNotEmpty(query.getRescores())) {
                    b.rescore(query.getRescores());
                }

                // 检索建议器 Suggester 配置
                if (CollectionUtil.isNotEmpty(query.getSuggesters())) {
                    b.suggest(s -> s.suggesters(query.getSuggesters()));
                }

                // kNN 向量检索（仅 Elasticsearch 模式下设置 top-level knn 参数，OpenSearch 模式已在 query 中融合）
                if (!spec.openSearch() && CollectionUtil.isNotEmpty(spec.effectiveKnn())) {
                    b.knn(spec.effectiveKnn());
                }

                return b;
            }, clazz);

            return response;
        } catch (Exception e) {
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchExceptionClassifier.classifyResultCode(e, SearchResultCode.SEARCH_DOCUMENT_GET_ERROR),
                    "Failed to execute search on index [" + index + "]: " + e.getMessage(), e);
        }
    }

    Query wrapTenantFilterIfNecessary(Query baseQuery, Class<?> clazz) {
        String tenantId = SearchTenantHelper.resolveActiveTenantId(properties, clazz);
        if (tenantId == null) {
            return baseQuery;
        }

        String tenantColumn = SearchTenantHelper.resolveTenantColumn(properties);
        Query tenantFilter = Query.of(q -> q.term(t -> t.field(tenantColumn).value(FieldValue.of(tenantId))));

        // 若原 query 为 BoolQuery，直接追加到 filter；否则包装为外层 BoolQuery
        if (baseQuery != null && baseQuery.isBool()) {
            BoolQuery boolQuery = baseQuery.bool();
            List<Query> filters = new ArrayList<>(boolQuery.filter());
            filters.add(tenantFilter);
            return Query.of(q -> q.bool(b -> b
                    .must(boolQuery.must())
                    .filter(filters)
                    .should(boolQuery.should())
                    .mustNot(boolQuery.mustNot())
                    .minimumShouldMatch(boolQuery.minimumShouldMatch())
            ));
        } else if (baseQuery != null) {
            return Query.of(q -> q.bool(b -> b
                    .must(baseQuery)
                    .filter(tenantFilter)
            ));
        } else {
            return Query.of(q -> q.bool(b -> b.filter(tenantFilter)));
        }
    }

    private List<KnnSearch> enrichKnnWithTenantFilter(List<KnnSearch> knnSearches, Class<?> clazz) {
        if (CollectionUtil.isEmpty(knnSearches)) {
            return knnSearches;
        }
        String tenantId = SearchTenantHelper.resolveActiveTenantId(properties, clazz);
        if (tenantId == null) {
            return knnSearches;
        }
        String tenantColumn = SearchTenantHelper.resolveTenantColumn(properties);
        Query tenantFilter = Query.of(q -> q.term(t -> t.field(tenantColumn).value(FieldValue.of(tenantId))));
        List<KnnSearch> scoped = new ArrayList<>(knnSearches.size());
        for (KnnSearch knn : knnSearches) {
            List<Query> mergedFilters = new ArrayList<>();
            if (CollectionUtil.isNotEmpty(knn.filter())) {
                mergedFilters.addAll(knn.filter());
            }
            mergedFilters.add(tenantFilter);
            scoped.add(KnnSearch.of(b -> {
                b.field(knn.field())
                        .queryVector(knn.queryVector())
                        .k(knn.k())
                        .filter(mergedFilters);
                if (knn.numCandidates() != null) {
                    b.numCandidates(knn.numCandidates());
                }
                if (knn.boost() != null) {
                    b.boost(knn.boost());
                }
                if (knn.similarity() != null) {
                    b.similarity(knn.similarity());
                }
                if (knn.innerHits() != null) {
                    b.innerHits(knn.innerHits());
                }
                return b;
            }));
        }
        return scoped;
    }

    private <T> List<SearchHit<T>> extractHits(ResponseBody<T> response, Class<T> clazz, boolean populateHighlights) {
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            return Collections.emptyList();
        }

        List<SearchHit<T>> resultList = new ArrayList<>();
        for (Hit<T> esHit : response.hits().hits()) {
            T source = esHit.source();
            Map<String, List<String>> highlights = esHit.highlight() != null
                    ? esHit.highlight()
                    : Collections.emptyMap();

            // 自动回填 @HighlightField（VO 投影场景延后至 VO 映射阶段回填，保护实体内存真明文）
            if (populateHighlights && source != null && !highlights.isEmpty()) {
                SearchMetadataHelper.populateHighlights(source, highlights);
            }

            List<String> sortValues = (esHit.sort() != null && !esHit.sort().isEmpty())
                    ? esHit.sort().stream().map(SearchTemplate::formatFieldValue).toList()
                    : Collections.emptyList();

            Map<String, List<SearchInnerHit>> innerHits = extractInnerHits(esHit);

            SearchHit<T> hit = SearchHit.<T>builder()
                    .id(esHit.id())
                    .index(esHit.index())
                    .content(source)
                    .score(esHit.score())
                    .seqNo(esHit.seqNo())
                    .primaryTerm(esHit.primaryTerm())
                    .version(esHit.version())
                    .routing(esHit.routing())
                    .sortValues(sortValues)
                    .highlightFields(highlights)
                    .innerHits(innerHits)
                    .build();
            resultList.add(hit);
        }
        return resultList;
    }

    @SuppressWarnings("unchecked")
    private <T> Map<String, List<SearchInnerHit>> extractInnerHits(Hit<T> esHit) {
        Map<String, InnerHitsResult> rawInnerHits = esHit.innerHits();
        if (rawInnerHits == null || rawInnerHits.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<SearchInnerHit>> converted = new LinkedHashMap<>(rawInnerHits.size());
        rawInnerHits.forEach((name, innerResult) -> {
            if (innerResult == null || innerResult.hits() == null || innerResult.hits().hits() == null) {
                converted.put(name, Collections.emptyList());
                return;
            }
            List<SearchInnerHit> items = innerResult.hits().hits().stream()
                    .map(ih -> {
                        Map<String, Object> src = ih.source() != null
                                ? (Map<String, Object>) ih.source().to(Map.class)
                                : Collections.emptyMap();
                        return SearchInnerHit.builder()
                                .id(ih.id())
                                .index(ih.index())
                                .score(ih.score())
                                .source(src != null ? src : Collections.emptyMap())
                                .build();
                    })
                    .toList();
            converted.put(name, items);
        });
        return converted;
    }

    private <T> SearchPage<T> buildSearchPage(ResponseBody<T> response, SearchQuery query, Class<T> clazz, boolean populateHighlights) {
        List<SearchHit<T>> hits = extractHits(response, clazz, populateHighlights);
        long total = 0L;
        if (response != null && response.hits() != null && response.hits().total() != null) {
            total = response.hits().total().value();
        }
        Map<String, Aggregate> aggregations = (response != null && response.aggregations() != null)
                ? response.aggregations()
                : Collections.emptyMap();
        Map<String, List<SearchSuggestResult>> suggestions = extractSuggestions(response);
        return SearchPage.<T>builder()
                .hits(hits)
                .total(total)
                .pageNum(query.getPageNum())
                .pageSize(query.getSize())
                .aggregations(aggregations)
                .suggestions(suggestions)
                .build();
    }

    private <T> Map<String, List<SearchSuggestResult>> extractSuggestions(ResponseBody<T> response) {
        if (response == null || response.suggest() == null || response.suggest().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<SearchSuggestResult>> result = new LinkedHashMap<>(response.suggest().size());
        response.suggest().forEach((name, suggestionList) -> {
            if (suggestionList == null || suggestionList.isEmpty()) {
                result.put(name, Collections.emptyList());
                return;
            }
            List<SearchSuggestResult> options = new ArrayList<>();
            for (Suggestion<T> suggestion : suggestionList) {
                if (suggestion == null) {
                    continue;
                }
                if (suggestion.isCompletion() && suggestion.completion() != null
                        && suggestion.completion().options() != null) {
                    for (CompletionSuggestOption<T> opt : suggestion.completion().options()) {
                        options.add(SearchSuggestResult.builder()
                                .text(opt.text())
                                .score(opt.score())
                                .id(opt.id())
                                .build());
                    }
                } else if (suggestion.isTerm() && suggestion.term() != null
                        && suggestion.term().options() != null) {
                    for (TermSuggestOption opt : suggestion.term().options()) {
                        options.add(SearchSuggestResult.builder()
                                .text(opt.text())
                                .score(opt.score())
                                .freq(opt.freq())
                                .highlighted(opt.highlighted())
                                .build());
                    }
                } else if (suggestion.isPhrase() && suggestion.phrase() != null
                        && suggestion.phrase().options() != null) {
                    for (PhraseSuggestOption opt : suggestion.phrase().options()) {
                        options.add(SearchSuggestResult.builder()
                                .text(opt.text())
                                .score(opt.score())
                                .highlighted(opt.highlighted())
                                .build());
                    }
                }
            }
            result.put(name, options);
        });
        return result;
    }

    private <T> SearchScrollResult<T> buildSearchScrollResult(SearchResponse<T> response, int pageSize, Class<T> clazz) {
        List<SearchHit<T>> hits = extractHits(response, clazz, true);
        List<String> nextSortValues = null;
        if (!hits.isEmpty()) {
            SearchHit<T> lastHit = hits.get(hits.size() - 1);
            if (lastHit.getSortValues() != null && !lastHit.getSortValues().isEmpty()) {
                nextSortValues = lastHit.getSortValues();
            }
        }
        boolean hasMore = hits.size() >= pageSize;
        return SearchScrollResult.<T>builder()
                .hits(hits)
                .nextSortValues(nextSortValues)
                .hasMore(hasMore)
                .build();
    }

    // ==================== 7. 底层逃生舱与受管观测执行器 (Execute) ====================

    /**
     * 原生底层逃生舱执行器
     *
     * @param callback 回调操作逻辑
     * @param <R>      返回类型泛型
     * @return 执行返回值
     */
    public <R> R execute(SearchCallback<R> callback) {
        Objects.requireNonNull(callback, "SearchCallback must not be null");
        return executeObserved("execute", null, null, null, null, false,
                () -> searchEngineClient.execute(callback));
    }

    private static final JacksonJsonpMapper SLOW_QUERY_JSONP_MAPPER = new JacksonJsonpMapper();
    private static final Pattern VECTOR_ARRAY_PATTERN = Pattern.compile("\"((?:query_)?vector)\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final int MAX_SLOW_QUERY_DSL_LENGTH = 2048;

    /**
     * 统一数据面/结构面受管观测执行模板（拦截链 + 指标采集 + 慢查询审计）
     */
    <R> R executeObserved(String operation,
                          String index,
                          Class<?> entityClass,
                          String routing,
                          SearchQuery query,
                          boolean bulkMetric,
                          Supplier<R> action) {
        SearchInvocationContext context = null;
        if (!interceptors.isEmpty()) {
            context = SearchInvocationContext.builder()
                    .operation(operation)
                    .index(index)
                    .entityClass(entityClass)
                    .routing(routing)
                    .query(query)
                    .engineType(searchEngineClient.getEngineType())
                    .build();
        }

        long startNanos = System.nanoTime();
        String outcome = "SUCCESS";
        int startedInterceptors = 0;
        R result;
        try {
            try {
                if (context != null) {
                    for (SearchInterceptor interceptor : interceptors) {
                        interceptor.beforeExecute(context);
                        startedInterceptors++;
                    }
                }
                result = action.get();
            } catch (Throwable t) {
                outcome = "ERROR";
                if (context != null && startedInterceptors > 0) {
                    for (int i = startedInterceptors - 1; i >= 0; i--) {
                        try {
                            interceptors.get(i).onError(context, t);
                        } catch (Throwable ignored) {
                            log.warn("SearchInterceptor.onError threw exception: {}", ignored.getMessage());
                        }
                    }
                }
                throw t;
            }
            if (context != null) {
                for (int i = startedInterceptors - 1; i >= 0; i--) {
                    interceptors.get(i).afterExecute(context, result);
                }
            }
            return result;
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            if (searchMetrics != null) {
                searchMetrics.recordDuration(operation, bulkMetric, outcome, elapsedNanos);
            }
            logSlowQueryIfNecessary(operation, index, query, outcome, elapsedNanos);
        }
    }

    private void logSlowQueryIfNecessary(String operation, String index, SearchQuery query, String outcome, long elapsedNanos) {
        AtengSearchProperties.ObservabilityProperties obs = properties.getObservability();
        if (obs == null || !log.isWarnEnabled()) {
            return;
        }
        Duration threshold = obs.getSlowQueryThreshold();
        if (threshold == null || threshold.isZero() || threshold.isNegative()) {
            return;
        }
        if (elapsedNanos < threshold.toNanos()) {
            return;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        String dslSummary = obs.isLogSlowQueryDsl() && query != null
                ? ", dsl=" + formatSlowQueryDsl(query, obs.isMaskVectorInLogs())
                : "";
        log.warn("Slow search operation detected [op={}, index={}, outcome={}, elapsedMs={}ms, thresholdMs={}ms{}]",
                operation, index, outcome, elapsedMs, threshold.toMillis(), dslSummary);
    }

    private static String formatSlowQueryDsl(SearchQuery query, boolean maskVectors) {
        StringBuilder sb = new StringBuilder();
        try {
            boolean hasQuery = query.getQuery() != null;
            boolean hasKnn = CollectionUtil.isNotEmpty(query.getKnnSearches());
            if (hasQuery && hasKnn) {
                sb.append("{\"query\":");
                JsonpUtils.toString(query.getQuery(), SLOW_QUERY_JSONP_MAPPER, sb);
                sb.append(",\"knn\":[");
                for (int i = 0; i < query.getKnnSearches().size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    JsonpUtils.toString(query.getKnnSearches().get(i), SLOW_QUERY_JSONP_MAPPER, sb);
                }
                sb.append("]}");
            } else if (hasQuery) {
                JsonpUtils.toString(query.getQuery(), SLOW_QUERY_JSONP_MAPPER, sb);
            } else if (hasKnn) {
                sb.append("{\"knn\":[");
                for (int i = 0; i < query.getKnnSearches().size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    JsonpUtils.toString(query.getKnnSearches().get(i), SLOW_QUERY_JSONP_MAPPER, sb);
                }
                sb.append("]}");
            } else {
                sb.append("{\"from\":").append(query.getFrom()).append(",\"size\":").append(query.getSize()).append('}');
            }
        } catch (Exception e) {
            return "<dsl serialization failed: " + e.getMessage() + ">";
        }
        String dsl = sb.toString();
        if (maskVectors) {
            dsl = maskVectorArrays(dsl);
        }
        if (dsl.length() > MAX_SLOW_QUERY_DSL_LENGTH) {
            return dsl.substring(0, MAX_SLOW_QUERY_DSL_LENGTH) + "...(truncated)";
        }
        return dsl;
    }

    private static String maskVectorArrays(String dsl) {
        Matcher matcher = VECTOR_ARRAY_PATTERN.matcher(dsl);
        if (!matcher.find()) {
            return dsl;
        }
        matcher.reset();
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            String field = matcher.group(1);
            String elements = matcher.group(2);
            int dims = 0;
            if (elements != null && !elements.isBlank()) {
                dims = elements.split(",").length;
            }
            String replacement = "\"" + field + "\":[\"<vector masked: " + dims + " dims>\"]";
            matcher.appendReplacement(masked, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(masked);
        return masked.toString();
    }

    // ==================== 私有辅助方法 ====================

    private static String formatFieldValue(FieldValue fv) {
        if (fv == null || fv.isNull()) {
            return null;
        }
        if (fv.isString()) {
            return fv.stringValue();
        }
        if (fv.isLong()) {
            return String.valueOf(fv.longValue());
        }
        if (fv.isDouble()) {
            return String.valueOf(fv.doubleValue());
        }
        if (fv.isBoolean()) {
            return String.valueOf(fv.booleanValue());
        }
        return fv._toJsonString();
    }
}
