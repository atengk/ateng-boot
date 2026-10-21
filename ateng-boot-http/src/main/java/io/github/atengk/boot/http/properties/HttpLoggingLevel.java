package io.github.atengk.boot.http.properties;

/**
 * 出站 HTTP 请求与响应日志输出级别枚举
 *
 * @author Ateng
 * @since 2026-10-21
 */
public enum HttpLoggingLevel {

    /**
     * 完全静默，不打印任何出站 HTTP 请求日志
     */
    NONE,

    /**
     * 基础摘要级别：仅打印 HTTP 动词、请求 URL、响应状态码及链路耗时
     */
    BASIC,

    /**
     * 请求头摘要级别：在 BASIC 基础上打印已安全掩码脱敏的出站请求头与响应头
     */
    HEADERS,

    /**
     * 全量调试级别：在 HEADERS 基础上输出终端可直接复制的 cURL 脚本与截断响应体
     */
    FULL
}
