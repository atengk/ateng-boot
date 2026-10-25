package io.github.atengk.boot.search.template;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.spi.SearchEngineClient;
import io.github.atengk.boot.search.util.SearchMetadataHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 搜索引擎统一操作门面模板
 * <p>
 * 提供文档索引、根据 ID 查询、根据 ID 删除等核心 DML 操作，内置实体元数据自动解析与强类型契约。
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
        Objects.requireNonNull(document, "document must not be null");
        String index = SearchMetadataHelper.resolveIndexName(document.getClass());
        return index(index, document);
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
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(document, "document must not be null");

        String documentId = SearchMetadataHelper.resolveDocumentId(document);

        return execute(client -> {
            IndexResponse response = client.index(builder -> {
                builder.index(index).document(document);
                if (StringUtils.hasText(documentId)) {
                    builder.id(documentId);
                }
                return builder;
            });
            log.debug("Indexed document [id={}] to index [{}], result: {}", response.id(), index, response.result());
            return response.id();
        });
    }

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
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(clazz, "clazz must not be null");
        Objects.requireNonNull(id, "document id must not be null");

        try {
            GetResponse<T> response = getClient().get(builder -> builder.index(index).id(id), clazz);
            if (response.found() && response.source() != null) {
                return response.source();
            }
            return null;
        } catch (ElasticsearchException ee) {
            if (ee.status() == 404) {
                log.debug("Document or index not found for index [{}] and id [{}]", index, id);
                return null;
            }
            throw new SearchException(SearchResultCode.SEARCH_DOCUMENT_GET_ERROR,
                    "Failed to get document [id=" + id + "] from index [" + index + "]: " + ee.getMessage(), ee);
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_DOCUMENT_GET_ERROR,
                    "Failed to get document [id=" + id + "] from index [" + index + "]: " + e.getMessage(), e);
        }
    }

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
        } catch (ElasticsearchException ee) {
            if (ee.status() == 404) {
                log.debug("Document or index not found for deletion on index [{}] and id [{}]", index, id);
                return false;
            }
            throw new SearchException(SearchResultCode.SEARCH_DOCUMENT_DELETE_ERROR,
                    "Failed to delete document [id=" + id + "] from index [" + index + "]: " + ee.getMessage(), ee);
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_DOCUMENT_DELETE_ERROR,
                    "Failed to delete document [id=" + id + "] from index [" + index + "]: " + e.getMessage(), e);
        }
    }

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
        } catch (ElasticsearchException ee) {
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Elasticsearch operation failed: " + ee.getMessage(), ee);
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Unexpected search operation error: " + e.getMessage(), e);
        }
    }
}
