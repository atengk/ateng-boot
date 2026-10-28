package io.github.atengk.boot.http.properties;

import lombok.Data;

/**
 * HTTP 正向出口网络代理配置属性
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
public class HttpProxyProperties {

    /**
     * 是否启用出口 HTTP 代理（默认：false）
     */
    private boolean enabled = false;

    /**
     * 代理服务器主机名或 IP 地址
     */
    private String host;

    /**
     * 代理服务器端口号（默认：8080）
     */
    private int port = 8080;

    /**
     * 代理认证用户名（可选，无需认证时留空）
     */
    private String username;

    /**
     * 代理认证密码（可选，无需认证时留空）
     */
    private String password;
}
