package io.github.atengk.boot.search.template;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.exception.SearchVersionConflictException;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import io.github.atengk.boot.search.util.SearchMetadataHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.ResponseException;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;

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
@RequiredArgsConstructor
public class SearchTemplate {

    private final SearchEngineClient searchEngineClient;

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

    // ==================== 1. 文档索引 (Index / Replace) ====================

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
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(document, "document must not be null");

        String documentId = SearchMetadataHelper.resolveDocumentId(document);

        try {
            IndexResponse response = getClient().index(builder -> {
                builder.index(index).document(document);
                if (StringUtils.hasText(documentId)) {
                    builder.id(documentId);
                }
                if (ifSeqNo != null && ifPrimaryTerm != null) {
                    builder.ifSeqNo(ifSeqNo).ifPrimaryTerm(ifPrimaryTerm);
                }
                return builder;
            });
            log.debug("Indexed document [id={}] to index [{}], result: {}", response.id(), index, response.result());
            return response.id();
        } catch (Exception e) {
            if (isVersionConflict(e)) {
                throw new SearchVersionConflictException(
                        "Document version conflict on index [" + index + "] id [" + documentId + "]: " + e.getMessage(),
                        ifSeqNo, ifPrimaryTerm, e);
            }
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchResultCode.SEARCH_DOCUMENT_INDEX_ERROR,
                    "Failed to index document [id=" + documentId + "] to index [" + index + "]: " + e.getMessage(), e);
        }
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
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return getById(index, clazz, id);
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
        SearchHit<T> hit = getHitById(index, clazz, id);
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
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return getHitById(index, clazz, id);
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
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(id, "document id must not be null");

        try {
            GetResponse<T> response = getClient().get(builder -> builder.index(index).id(id), clazz);
            if (response.found() && response.source() != null) {
                return SearchHit.<T>builder()
                        .id(response.id())
                        .index(response.index())
                        .content(response.source())
                        .seqNo(response.seqNo())
                        .primaryTerm(response.primaryTerm())
                        .version(response.version())
                        .routing(response.routing())
                        .build();
            }
            return null;
        } catch (Exception e) {
            if (extractStatusCode(e) == 404) {
                log.debug("Document or index not found for index [{}] and id [{}]", index, id);
                return null;
            }
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchResultCode.SEARCH_DOCUMENT_GET_ERROR,
                    "Failed to get document [id=" + id + "] from index [" + index + "]: " + e.getMessage(), e);
        }
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
        return doUpdate(index, id, partialDoc, docAsUpsert, ifSeqNo, ifPrimaryTerm);
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
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return update(index, id, partialDoc, docAsUpsert, ifSeqNo, ifPrimaryTerm);
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
        return doUpdate(index, id, partialDoc, docAsUpsert, ifSeqNo, ifPrimaryTerm);
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
        Objects.requireNonNull(partialDoc, "partialDoc must not be null");
        String index = SearchMetadataHelper.resolveIndexName(partialDoc.getClass());
        String documentId = SearchMetadataHelper.resolveDocumentId(partialDoc);
        if (!StringUtils.hasText(documentId)) {
            throw new IllegalArgumentException("Cannot update document without an ID. Ensure @SearchId is configured on the entity.");
        }
        return update(index, documentId, partialDoc, docAsUpsert, ifSeqNo, ifPrimaryTerm);
    }

    private <T> boolean doUpdate(String index, String id, T partialDoc, boolean docAsUpsert,
                                 Long ifSeqNo, Long ifPrimaryTerm) {
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(id, "document id must not be null");
        Objects.requireNonNull(partialDoc, "partialDoc must not be null");

        try {
            UpdateResponse<Void> response = getClient().update(builder -> {
                builder.index(index)
                        .id(id)
                        .doc(partialDoc)
                        .docAsUpsert(docAsUpsert);
                if (ifSeqNo != null && ifPrimaryTerm != null) {
                    builder.ifSeqNo(ifSeqNo).ifPrimaryTerm(ifPrimaryTerm);
                }
                return builder;
            }, Void.class);

            log.debug("Updated document [id={}] on index [{}], result: {}", id, index, response.result());
            return response.result() == Result.Updated || response.result() == Result.Created || response.result() == Result.NoOp;
        } catch (Exception e) {
            if (isVersionConflict(e)) {
                throw new SearchVersionConflictException(
                        "Document version conflict on update for index [" + index + "] id [" + id + "]: " + e.getMessage(),
                        ifSeqNo, ifPrimaryTerm, e);
            }
            if (extractStatusCode(e) == 404) {
                log.debug("Document or index not found for update on index [{}] and id [{}]", index, id);
                return false;
            }
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to update document [id=" + id + "] on index [" + index + "]: " + e.getMessage(), e);
        }
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
        Objects.requireNonNull(clazz, "clazz must not be null");
        String index = SearchMetadataHelper.resolveIndexName(clazz);
        return deleteById(index, id);
    }

    /**
     * 根据文档 ID 从指定索引删除文档
     *
     * @param index 目标索引名
     * @param id    文档 ID
     * @return 是否成功删除（若文档不存在则返回 false）
     */
    public boolean deleteById(String index, String id) {
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(id, "document id must not be null");

        try {
            DeleteResponse response = getClient().delete(builder -> builder.index(index).id(id));
            log.debug("Deleted document [id={}] from index [{}], result: {}", id, index, response.result());
            return response.result() == Result.Deleted;
        } catch (Exception e) {
            if (extractStatusCode(e) == 404) {
                log.debug("Document or index not found for deletion on index [{}] and id [{}]", index, id);
                return false;
            }
            if (e instanceof SearchException se) {
                throw se;
            }
            throw new SearchException(SearchResultCode.SEARCH_DOCUMENT_DELETE_ERROR,
                    "Failed to delete document [id=" + id + "] from index [" + index + "]: " + e.getMessage(), e);
        }
    }

    // ==================== 5. 底层逃生舱 (Execute) ====================

    /**
     * 原生底层逃生舱执行器
     *
     * @param callback 回调操作逻辑
     * @param <R>      返回类型泛型
     * @return 执行返回值
     */
    public <R> R execute(SearchCallback<R> callback) {
        Objects.requireNonNull(callback, "SearchCallback must not be null");
        try {
            return callback.doInSearch(getClient());
        } catch (SearchException se) {
            throw se;
        } catch (Exception e) {
            if (isVersionConflict(e)) {
                throw new SearchVersionConflictException("Document version conflict: " + e.getMessage(), e);
            }
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Elasticsearch operation failed: " + e.getMessage(), e);
        }
    }

    // ==================== 私有辅助方法 ====================

    private static int extractStatusCode(Throwable t) {
        if (t == null) {
            return -1;
        }
        if (t instanceof ElasticsearchException ee) {
            return ee.status();
        }
        if (t instanceof ResponseException re) {
            return re.getResponse().getStatusLine().getStatusCode();
        }
        return extractStatusCode(t.getCause());
    }

    private static boolean isVersionConflict(Throwable t) {
        if (t == null) {
            return false;
        }
        if (extractStatusCode(t) == 409) {
            return true;
        }
        String msg = t.getMessage();
        if (msg != null && (msg.contains("version_conflict_engine_exception") || msg.contains("version conflict"))) {
            return true;
        }
        return false;
    }
}
