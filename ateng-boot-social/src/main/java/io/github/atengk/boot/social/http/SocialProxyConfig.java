package io.github.atengk.boot.social.http;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * 社交应用出站网络代理配置模型
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialProxyConfig {

    /**
     * 代理协议类型 (HTTP / SOCKS)
     */
    private Proxy.Type type;

    /**
     * 代理服务器主机名或 IP 地址
     */
    private String host;

    /**
     * 代理服务器端口号
     */
    private Integer port;

    /**
     * 转换为 Java 原生网络 Proxy 对象
     *
     * @return 原生 Proxy，若未配置有效 host/port 则返回 null
     */
    public Proxy toProxy() {
        if (host == null || host.isBlank() || port == null || port <= 0) {
            return null;
        }
        Proxy.Type proxyType = type != null ? type : Proxy.Type.HTTP;
        return new Proxy(proxyType, new InetSocketAddress(host.trim(), port));
    }
}
