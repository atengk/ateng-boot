package io.github.atengk.boot.storage.properties;

import lombok.Data;

/**
 * 本地磁盘存储配置属性
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
public class LocalStorageProperties {

    /**
     * 本地存储物理基准目录（必填，支持绝对路径或相对路径）
     */
    private String basePath;

    /**
     * 访问域名或服务端基础 URL（用于拼接文件外链，例如 http://localhost:8080）
     */
    private String domain;

    /**
     * HMAC-SHA256 签名密钥（用于预签名防盗链下载，若未配置则系统启动时自动随机生成）
     */
    private String secretKey;

    /**
     * 预签名只读安全下载端点路径（默认 /storage/local/download）
     */
    private String downloadEndpoint = "/storage/local/download";
}
