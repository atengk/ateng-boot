package io.github.atengk.boot.tcp.properties;

import io.github.atengk.boot.tcp.enums.ClientAuth;
import lombok.Getter;
import lombok.Setter;

/**
 * TCP TLS/SSL 链路加密与双向证书认证配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
public class TcpSslProperties {

    /**
     * 是否启用 TLS/SSL 传输加密（默认 false）
     */
    private boolean enabled = false;

    /**
     * 服务端或客户端证书密钥库文件路径（支持 classpath: 或物理绝对路径）
     */
    private String keyStore;

    /**
     * 证书密钥库访问密码
     */
    private String keyStorePassword;

    /**
     * 证书密钥库类型（默认 PKCS12，兼容 JKS）
     */
    private String keyStoreType = "PKCS12";

    /**
     * 信任证书库文件路径（双向认证或校验服务端 CA 时使用）
     */
    private String trustStore;

    /**
     * 信任证书库访问密码
     */
    private String trustStorePassword;

    /**
     * 信任证书库类型（默认 PKCS12，兼容 JKS）
     */
    private String trustStoreType = "PKCS12";

    /**
     * 服务端对客户端的证书认证模式（默认 NONE，双向认证可指定为 NEED）
     */
    private ClientAuth clientAuth = ClientAuth.NONE;
}
