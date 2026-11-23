package io.github.atengk.boot.http.properties;

import lombok.Data;
import org.springframework.util.StringUtils;

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

    /**
     * 客户端私钥证书密钥库路径（支持 classpath: 或物理绝对路径，例如：classpath:certs/client.p12）
     * 用于金融级、银行与政企支付系统等场景下的双向 mTLS 证书出示
     */
    private String keyStore;

    /**
     * 客户端私钥密钥库访问密码（留空表示无密码）
     */
    private String keyStorePassword;

    /**
     * 密钥库格式类型（如 PKCS12, JKS，默认：PKCS12）
     */
    private String keyStoreType = "PKCS12";

    /**
     * 是否配置了客户端私钥证书
     *
     * @return true 表示已配置有效 keyStore
     */
    public boolean hasKeyStore() {
        return StringUtils.hasText(this.keyStore);
    }
}
