package io.github.atengk.boot.http.properties;

import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /**
     * 不经过代理的直连主机名或 IP 白名单（支持通配符或网段匹配）
     * 默认包含本地回环与常见局域网私有网段，防御内网微服务流量被意外代理路由
     */
    private List<String> nonProxyHosts = new ArrayList<>(List.of(
            "localhost",
            "127.0.0.1",
            "10.*",
            "172.16.*",
            "192.168.*"
    ));

    /**
     * 判断指定的目标主机名或 IP 是否匹配非代理直连白名单
     *
     * @param host 目标请求主机名或 IP
     * @return true 表示命中白名单无需经过代理直连，false 表示需要经过代理路由
     */
    public boolean isNonProxyHost(String host) {
        if (!StringUtils.hasText(host) || this.nonProxyHosts == null || this.nonProxyHosts.isEmpty()) {
            return false;
        }
        String cleanHost = host.trim().toLowerCase(Locale.ROOT);
        for (String pattern : this.nonProxyHosts) {
            if (!StringUtils.hasText(pattern)) {
                continue;
            }
            String cleanPattern = pattern.trim().toLowerCase(Locale.ROOT);
            if ("*".equals(cleanPattern) || cleanPattern.equals(cleanHost)) {
                return true;
            }
            if (cleanPattern.startsWith("*.") && cleanHost.endsWith(cleanPattern.substring(1))) {
                return true;
            }
            if (cleanPattern.endsWith(".*") && cleanHost.startsWith(cleanPattern.substring(0, cleanPattern.length() - 1))) {
                return true;
            }
            if (cleanPattern.contains("*")) {
                String regex = "^" + cleanPattern.replace(".", "\\.").replace("*", ".*") + "$";
                if (cleanHost.matches(regex)) {
                    return true;
                }
            }
        }
        return false;
    }
}
