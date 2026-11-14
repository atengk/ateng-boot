package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单个命名客户端实例治理元数据条目视图对象
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistryClientItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户端逻辑名称
     */
    private String clientName;

    /**
     * 是否已在 RestClientRegistry 注册实例化
     */
    private boolean registered;

    /**
     * 配置或解析后的目标基准 URL
     */
    private String baseUrl;

    /**
     * 是否开启内网四维上下文透传
     */
    private boolean propagateContext;

    /**
     * 是否启用实例级弹性重试
     */
    private boolean retryEnabled;

    /**
     * 是否启用实例级独立代理
     */
    private boolean proxyEnabled;

    /**
     * 代理端点摘要 (host:port)，未启用时为 null
     */
    private String proxyEndpoint;

    /**
     * 是否配置实例级独立 SSL/mTLS 密钥库或信任库
     */
    private boolean sslEnabled;

    /**
     * SSL 密钥库类型（不含任何密码机密）
     */
    private String keyStoreType;
}
