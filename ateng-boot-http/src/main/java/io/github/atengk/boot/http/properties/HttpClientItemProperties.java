package io.github.atengk.boot.http.properties;

import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;

/**
 * 命名 HTTP 客户端专属个性化配置项（用于覆盖全局默认配置）
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
public class HttpClientItemProperties {

    /**
     * 目标远端服务的基础请求根路径（Base URL，例如：https://api.example.com）
     */
    private String baseUrl;

    /**
     * 该客户端专属的建联超时时间（若为空则继承全局 pool.connectTimeout）
     */
    private Duration connectTimeout;

    /**
     * 该客户端专属的读取响应超时时间（若为空则继承全局 pool.readTimeout）
     */
    private Duration readTimeout;

    /**
     * 是否允许向该目标服务透传当前上下文请求头（TraceId、TenantId、UserId，默认：true）
     * 对接公网非信任第三方（如微信、支付宝、外部 SaaS）时强制显式配置为 false，防止内网敏感信息外泄
     */
    private boolean propagateContext = true;

    /**
     * 该客户端专属的默认请求头（支持 ${...} 占位符解析）
     * 自动融合至该命名客户端所有出站请求中
     */
    private Map<String, String> defaultHeaders;

    /**
     * 该客户端专属的正向代理配置（若配置则覆盖全局 proxy 设置）
     */
    @NestedConfigurationProperty
    private HttpProxyProperties proxy;

    /**
     * 该客户端专属的 SSL/mTLS 配置（支持客户端私钥证书与 insecure 跳过校验，覆盖全局 ssl 设置）
     */
    @NestedConfigurationProperty
    private HttpSslProperties ssl;

    /**
     * 客户端私钥证书密钥库路径（便捷配置，亦可在 ssl.key-store 中配置）
     */
    private String keyStore;

    /**
     * 客户端私钥密钥库访问密码
     */
    private String keyStorePassword;

    /**
     * 密钥库格式类型（如 PKCS12, JKS，默认：PKCS12）
     */
    private String keyStoreType;

    /**
     * 该客户端专属的弹性指数退避重试配置（覆盖全局 retry 配置）
     */
    @NestedConfigurationProperty
    private HttpRetryProperties retry;

    /**
     * 解析该客户端最终生效的 SSL/mTLS 配置
     *
     * @return HttpSslProperties，若未配置返回 null
     */
    public HttpSslProperties getResolvedSsl() {
        if (this.ssl != null) {
            return this.ssl;
        }
        if (StringUtils.hasText(this.keyStore)) {
            HttpSslProperties synthesizedSsl = new HttpSslProperties();
            synthesizedSsl.setKeyStore(this.keyStore);
            synthesizedSsl.setKeyStorePassword(this.keyStorePassword);
            if (StringUtils.hasText(this.keyStoreType)) {
                synthesizedSsl.setKeyStoreType(this.keyStoreType);
            }
            return synthesizedSsl;
        }
        return null;
    }
}
