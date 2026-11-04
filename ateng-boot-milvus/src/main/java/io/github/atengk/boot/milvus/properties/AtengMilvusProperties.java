package io.github.atengk.boot.milvus.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Milvus 向量数据库客户端强类型配置属性
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.milvus")
public class AtengMilvusProperties {

    /**
     * 是否启用 Milvus 自动装配
     */
    private boolean enabled = true;

    /**
     * Milvus 节点连接地址 (若配置 uri 则优先使用 uri，如 "http://127.0.0.1:19530")
     */
    private String uri;

    /**
     * Milvus 服务端主机地址 (当未配置 uri 时生效)
     */
    private String host = "127.0.0.1";

    /**
     * Milvus 服务端端口号 (当未配置 uri 时生效)
     */
    private int port = 19530;

    /**
     * 认证 Token (支持 API Key 或 "username:password" 格式)
     */
    private String token;

    /**
     * 用户名 (当未提供 token 时，可与 password 组合鉴权)
     */
    private String username;

    /**
     * 密码 (当未提供 token 时，可与 username 组合鉴权)
     */
    private String password;

    /**
     * 默认连接的逻辑数据库名称
     */
    private String database = "default";

    /**
     * 是否启用 TLS 链路加密传输
     */
    private boolean secure = false;

    /**
     * gRPC 连接建立超时阈值
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * gRPC KeepAlive 保活心跳间隔时间
     */
    private Duration keepAliveTime = Duration.ofSeconds(55);

    /**
     * gRPC KeepAlive 保活响应超时阈值
     */
    private Duration keepAliveTimeout = Duration.ofSeconds(20);

    /**
     * gRPC 通道空闲超时阈值
     */
    private Duration idleTimeout = Duration.ofHours(24);

    /**
     * 默认读写一致性级别
     */
    private MilvusConsistencyLevel consistencyLevel = MilvusConsistencyLevel.BOUNDED;

    /**
     * 写入时若物理分区不存在是否自动容错创建
     */
    private boolean autoCreatePartition = false;

    /**
     * 是否启用多租户切库自动路由 (开启后根据 TenantContextHolder.getTenantId() 自动切入租户专属数据库)
     */
    private boolean tenantDatabaseRouting = false;

    /**
     * 计算并获取有效的连接端点 URI
     *
     * @return 格式化后的 Milvus 连接 URI
     */
    public String resolveEffectiveUri() {
        if (uri != null && !uri.isBlank()) {
            return uri.trim();
        }
        String protocol = secure ? "https" : "http";
        return String.format("%s://%s:%d", protocol, host, port);
    }
}
