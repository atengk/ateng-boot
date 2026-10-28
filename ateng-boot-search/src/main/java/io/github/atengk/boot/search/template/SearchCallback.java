package io.github.atengk.boot.search.template;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import java.util.Objects;
import java.util.function.Function;

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

    /**
     * 将标准 Function 适配为 SearchCallback
     *
     * @param function 标准函数
     * @param <R>      返回结果泛型
     * @return SearchCallback 适配实例
     */
    static <R> SearchCallback<R> of(Function<ElasticsearchClient, R> function) {
        Objects.requireNonNull(function, "function must not be null");
        return function::apply;
    }
}
