package io.github.atengk.boot.tcp.enums;

/**
 * TLS/SSL 客户端认证模式枚举
 *
 * @author Ateng
 * @since 2026-10-26
 */
public enum ClientAuth {

    /**
     * 不要求客户端认证（单向加密）
     */
    NONE,

    /**
     * 请求客户端证书但非强制（弱双向认证）
     */
    WANT,

    /**
     * 强制要求客户端提供合法证书（强双向认证 mTLS）
     */
    NEED
}
