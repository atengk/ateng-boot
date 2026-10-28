package io.github.atengk.boot.http.properties;

import lombok.Data;

/**
 * HTTP SSL/TLS 网络安全配置属性
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
public class HttpSslProperties {

    /**
     * 是否跳过 SSL 证书合法性与主机名校验（默认：false）
     * 开启后将信任所有证书（包含自签名证书），适用于内网私有服务或测试环境对接
     */
    private boolean insecure = false;
}
