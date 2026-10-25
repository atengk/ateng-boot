package io.github.atengk.boot.search.enums;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 搜索模块专属受管业务结果码枚举 (60001~60020)
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@AllArgsConstructor
public enum SearchResultCode implements IResultCode {

    /**
     * 搜索引擎客户端异常
     */
    SEARCH_CLIENT_ERROR(60001, "搜索引擎客户端异常"),

    /**
     * 搜索引擎连接异常
     */
    SEARCH_ENGINE_CONNECT_ERROR(60002, "搜索引擎连接异常"),

    /**
     * 指定的搜索索引或别名不存在
     */
    SEARCH_INDEX_NOT_FOUND(60003, "指定的搜索索引或别名不存在"),

    /**
     * 指定的搜索文档不存在
     */
    SEARCH_DOCUMENT_NOT_FOUND(60004, "指定的搜索文档不存在"),

    /**
     * 文档版本冲突（乐观并发控制拦截）
     */
    SEARCH_VERSION_CONFLICT(60005, "文档版本冲突（乐观并发控制拦截）"),

    /**
     * 批量写入操作存在局部失败文档
     */
    SEARCH_BULK_PARTIAL_ERROR(60006, "批量写入操作存在局部失败文档"),

    /**
     * 分页深度超过最大窗口限制
     */
    SEARCH_PAGINATION_EXCEEDED(60007, "分页深度超过最大窗口限制"),

    /**
     * 该索引操作必须显式提供分片路由键
     */
    SEARCH_ROUTING_REQUIRED(60008, "该索引操作必须显式提供分片路由键"),

    /**
     * 搜索引擎通用操作执行失败
     */
    SEARCH_OPERATION_ERROR(60009, "搜索引擎通用操作执行失败"),

    /**
     * 搜索文档索引写入失败
     */
    SEARCH_DOCUMENT_INDEX_ERROR(60010, "搜索文档索引写入失败"),

    /**
     * 搜索文档查询获取失败
     */
    SEARCH_DOCUMENT_GET_ERROR(60011, "搜索文档查询获取失败"),

    /**
     * 搜索文档删除失败
     */
    SEARCH_DOCUMENT_DELETE_ERROR(60012, "搜索文档删除失败");

    private final Integer code;
    private final String msg;
}
