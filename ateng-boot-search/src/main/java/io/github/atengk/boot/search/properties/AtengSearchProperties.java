package io.github.atengk.boot.search.properties;

import io.github.atengk.boot.search.enums.SearchEngineType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 搜索引擎配置属性类
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.search")
public class AtengSearchProperties {

    /**
     * 是否启用搜索基础设施Starter
     */
    private boolean enabled = true;

    /**
     * 底层搜索引擎类型（默认 ELASTICSEARCH）
     */
    private SearchEngineType engine = SearchEngineType.ELASTICSEARCH;

    /**
     * 搜索引擎服务节点地址列表（如 http://localhost:9200）
     */
    private List<String> uris = new ArrayList<>(Collections.singletonList("http://localhost:9200"));

    /**
     * 认证用户名（可选）
     */
    private String username;

    /**
     * 认证密码（可选）
     */
    private String password;

    /**
     * 云原生 API Key 凭证（可选，若配置优先于用户名密码）
     */
    private String apiKey;

    /**
     * 反向代理网关子路径前缀（如 /es-cluster）
     */
    private String pathPrefix;

    /**
     * 网络连接建立超时时间（默认 5 秒）
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * 网络套接字读取超时时间（默认 30 秒）
     */
    private Duration socketTimeout = Duration.ofSeconds(30);

    /**
     * 连接池最大并发连接数（默认 100）
     */
    private int maxConnections = 100;

    /**
     * 连接池单节点路由最大并发连接数（默认 50）
     */
    private int maxConnectionsPerRoute = 50;

    /**
     * 连接保活时长（默认 30 秒）
     */
    private Duration keepAlive = Duration.ofSeconds(30);

    /**
     * TLS/SSL 安全配置
     */
    @NestedConfigurationProperty
    private SslProperties ssl = new SslProperties();

    /**
     * 多租户隔离配置
     */
    @NestedConfigurationProperty
    private MultiTenancyProperties multiTenancy = new MultiTenancyProperties();

    /**
     * 分页治理配置
     */
    @NestedConfigurationProperty
    private PaginationProperties pagination = new PaginationProperties();

    /**
     * 批量缓冲写入治理配置 (BulkProcessor)
     */
    @NestedConfigurationProperty
    private BulkProcessorProperties bulk = new BulkProcessorProperties();

    @Data
    public static class SslProperties {
        /**
         * 是否启用 SSL/TLS
         */
        private boolean enabled = false;

        /**
         * TrustStore 证书文件路径
         */
        private String trustStorePath;

        /**
         * TrustStore 证书密码
         */
        private String trustStorePassword;

        /**
         * 本地开发沙箱是否跳过自签名证书合法性校验（开发联调便捷开关）
         */
        private boolean insecureSkipVerify = false;
    }

    @Data
    public static class MultiTenancyProperties {
        /**
         * 是否开启多租户自动隐式过滤
         */
        private boolean enabled = true;

        /**
         * 多租户字段名（默认 tenant_id）
         */
        private String column = "tenant_id";
    }

    @Data
    public static class PaginationProperties {
        /**
         * 浅分页最大结果窗口阈值，超过将刚性熔断（默认 10000）
         */
        private int maxResultWindow = 10000;
    }

    @Data
    public static class BulkProcessorProperties {
        /**
         * 是否启用默认受管 BulkProcessor Bean
         */
        private boolean enabled = true;

        /**
         * 批量提交条数阈值（默认 1000 条）
         */
        private int batchSize = 1000;

        /**
         * 定时强制刷盘时间间隔（默认 1 秒）
         */
        private Duration flushInterval = Duration.ofSeconds(1);

        /**
         * 缓冲估算字节容量阈值（默认 5MB）
         */
        private DataSize bufferSize = DataSize.ofMegabytes(5);

        /**
         * 最大并发执行的 Flush 请求数（默认 1，>0 表示异步并发提交，0 表示同步阻塞提交）
         */
        private int concurrentRequests = 1;

        /**
         * 缓冲队列最大文档积压容量（默认 10000 条，超出将触发微退避背压防爆）
         */
        private int maxQueueCapacity = 10000;

        /**
         * 优雅停机排空最大等待超时时间（默认 10 秒）
         */
        private Duration awaitCloseTimeout = Duration.ofSeconds(10);
    }
}
