package io.github.atengk.boot.http.properties;

/**
 * HTTP 通信底层引擎类型枚举
 *
 * @author Ateng
 * @since 2026-10-21
 */
public enum HttpEngineType {

    /**
     * 自动探测模式：优先使用 Apache HttpClient 5，类路径缺失时自动平滑回退至 JDK 17 原生 HttpClient
     */
    AUTO,

    /**
     * 强制指定 Apache HttpClient 5 生产级高并发池化连接引擎
     */
    HTTPCLIENT5,

    /**
     * 强制指定 JDK 17 原生 HttpClient 引擎（零外部网络库依赖，虚拟线程友好）
     */
    JDK
}
