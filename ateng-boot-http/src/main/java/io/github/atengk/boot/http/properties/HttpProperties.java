package io.github.atengk.boot.http.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ateng-boot HTTP 通信底座根配置属性类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.http")
public class HttpProperties {

    /**
     * 是否启用 HTTP 模块自动装配（默认：true）
     */
    private boolean enabled = true;

    /**
     * 底层通信引擎选型（默认：auto 自动探测，优先使用 Apache HttpClient 5，缺省回退 JDK 17 原生 Client）
     */
    private HttpEngineType engine = HttpEngineType.AUTO;

    /**
     * 全局连接池核心参数配置
     */
    @NestedConfigurationProperty
    private HttpPoolProperties pool = new HttpPoolProperties();

    /**
     * 全局 SSL/TLS 证书网络安全配置
     */
    @NestedConfigurationProperty
    private HttpSslProperties ssl = new HttpSslProperties();

    /**
     * 全局出口正向代理配置
     */
    @NestedConfigurationProperty
    private HttpProxyProperties proxy = new HttpProxyProperties();

    /**
     * 命名客户端专属配置映射表（按客户端名称映射独立个性化配置，留待后续阶段联动）
     */
    private Map<String, HttpClientItemProperties> clients = new LinkedHashMap<>();
}
