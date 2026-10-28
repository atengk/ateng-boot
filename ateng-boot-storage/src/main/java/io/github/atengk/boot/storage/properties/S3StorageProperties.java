package io.github.atengk.boot.storage.properties;

import lombok.Data;

/**
 * S3 兼容协议存储配置属性
 * 支持自建 MinIO、AWS S3、阿里云 OSS、腾讯云 COS、华为云 OBS 等标准 S3 协议对象存储
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
public class S3StorageProperties {

    /**
     * S3 服务端点 URL（例如 http://127.0.0.1:9000 或 https://oss-cn-hangzhou.aliyuncs.com）
     */
    private String endpoint;

    /**
     * 目标存储桶名称（Bucket）
     */
    private String bucket;

    /**
     * 访问凭据 Access Key
     */
    private String accessKey;

    /**
     * 访问密钥 Secret Key
     */
    private String secretKey;

    /**
     * 地域标识（默认 us-east-1）
     */
    private String region = "us-east-1";

    /**
     * 是否启用路径样式访问（Path-style Access）
     * MinIO 等自建存储推荐为 true；公有云如 AWS、阿里云通常推荐为 false（虚拟主机域名方式）
     */
    private boolean pathStyle = true;

    /**
     * 自定义访问域名或 CDN 加速域名（可选，用于拼接预签名外链）
     */
    private String domain;
}
