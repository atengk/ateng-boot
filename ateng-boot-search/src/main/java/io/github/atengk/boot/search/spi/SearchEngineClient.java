package io.github.atengk.boot.search.spi;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.template.SearchCallback;

import java.io.Closeable;
import java.util.Objects;

/**
 * 搜索引擎客户端 SPI 统一抽象接口
 * <p>
 * 解耦 Elasticsearch 8.x 与 OpenSearch 原生 SDK 差异，提供底层驱动的生命周期管理与多态逃生舱。
 *
 * @author Ateng
 * @since 2026-10-25
 */
public interface SearchEngineClient extends Closeable {

    /**
     * 获取当前生效的搜索引擎类型
     *
     * @return 引擎枚举类型
     */
    SearchEngineType getEngineType();

    /**
     * 获取指定类型的原生客户端驱动实例（底层逃生舱）
     *
     * @param clientClass 客户端类类型
     * @param <T>         客户端泛型
     * @return 原生驱动实例
     */
    <T> T getNativeClient(Class<T> clientClass);

    /**
     * 获取 Elasticsearch 官方强类型客户端
     *
     * @return ElasticsearchClient 实例
     * @throws UnsupportedOperationException 当底层引擎非 Elasticsearch 时抛出
     */
    default ElasticsearchClient getElasticsearchClient() {
        return getNativeClient(ElasticsearchClient.class);
    }

    /**
     * 原生强类型客户端回调执行器（提供底层逃生舱与受管异常闭环）
     *
     * @param callback 回调操作逻辑
     * @param <R>      返回类型泛型
     * @return 执行结果
     */
    default <R> R execute(SearchCallback<R> callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        try {
            return callback.doInSearch(getElasticsearchClient());
        } catch (SearchException se) {
            throw se;
        } catch (Exception e) {
            throw new SearchException(SearchResultCode.SEARCH_OPERATION_ERROR,
                    "Failed to execute elasticsearch callback action: " + e.getMessage(), e);
        }
    }
}
