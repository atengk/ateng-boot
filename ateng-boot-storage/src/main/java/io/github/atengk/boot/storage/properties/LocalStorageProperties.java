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

    /**
     * 预签名写入安全直传端点路径（默认 /storage/local/upload）
     */
    private String uploadEndpoint = "/storage/local/upload";

    /**
     * 是否开启本地存储预签名下载与直传端点自动装配（总开关，混合多源包含 local 源时同样生效，默认 true）
     */
    private boolean endpointEnabled = true;

    /**
     * 是否开启本地存储预签名只读下载端点自动装配（受总开关 endpoint-enabled 约束，默认 true）
     */
    private boolean downloadEndpointEnabled = true;

    /**
     * 是否开启本地存储预签名直传写入端点自动装配（受总开关 endpoint-enabled 约束，默认 true；设为 false 可交由上层自建 API 接口实现自定义权限控制）
     */
    private boolean uploadEndpointEnabled = true;

    /**
     * 是否自动将本地存储预签名只读下载端点加入 Sa-Token 免登录白名单（默认 false；需同时引入 ateng-boot-satoken 且下载端点已启用方生效）
     */
    private boolean whitelistPresignedDownload = false;

    /**
     * 是否自动将本地存储预签名直传写入端点加入 Sa-Token 免登录白名单（默认 false；开启后仅由 HMAC 签名与 StorageGuard 校验，不校验 Sa-Token 登录态）
     */
    private boolean whitelistPresignedUpload = false;
}
