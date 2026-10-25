package io.github.atengk.boot.search.template;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * 原生 Elasticsearch 客户端回调接口（底层逃生舱）
 *
 * @param <R> 返回结果泛型
 * @author Ateng
 * @since 2026-10-25
 */
@FunctionalInterface
public interface SearchCallback<R> {

    /**
     * 在 ElasticsearchClient 原生客户端上下文中执行自定义操作
     *
     * @param client 原生强类型客户端
     * @return 执行结果
     * @throws Exception 执行异常
     */
    R doInSearch(ElasticsearchClient client) throws Exception;
}
