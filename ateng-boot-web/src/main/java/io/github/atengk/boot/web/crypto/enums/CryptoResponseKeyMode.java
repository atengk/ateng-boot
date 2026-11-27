package io.github.atengk.boot.web.crypto.enums;

/**
 * API 传输层出站响应加密密钥来源模式枚举
 * 用于区分基于客户端非对称公钥封包与面向纯 Web SPA 无静态私钥的请求会话密钥回包模式
 *
 * @author Ateng
 * @since 2026-11-27
 */
public enum CryptoResponseKeyMode {

    /**
     * 跟随全局配置（仅用于 {@link io.github.atengk.boot.web.crypto.annotation.ApiEncrypt} 注解默认占位）
     */
    DEFAULT,

    /**
     * 客户端公钥模式（默认）：服务端生成全新随机对称会话密钥，并使用客户端非对称公钥封装写入 {@code X-Encrypt-Key}
     */
    CLIENT_PUBLIC_KEY,

    /**
     * 请求会话密钥回包模式（面向纯 Web SPA 无静态私钥场景）：
     * 复用本次入站请求 {@code X-Encrypt-Key} 解封得到的一次性对称会话密钥，生成全新随机 12 字节 IV 加密响应并省略 {@code X-Encrypt-Key} 响应头
     */
    REQUEST_SESSION_KEY
}
