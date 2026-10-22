package io.github.atengk.boot.ftp.properties;

import io.github.atengk.boot.ftp.enums.FtpsDataChannelProtection;
import lombok.Data;

/**
 * FTPS 安全加密传输配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
public class FtpsProperties {

    /**
     * 是否启用 FTPS 安全加密传输模式
     */
    private boolean enabled = false;

    /**
     * SSL/TLS 加密协议版本（默认 TLSv1.3，兼容 TLSv1.2）
     */
    private String protocol = "TLSv1.3";

    /**
     * 是否为隐式 FTPS（true: 端口 990 直接建立 TLS 握手；false: 端口 21 通过 AUTH TLS 显式升级，默认 false）
     */
    private boolean implicit = false;

    /**
     * 数据传输通道安全加密保护级别（默认 PRIVATE 即 PROT P 全链路加密）
     */
    private FtpsDataChannelProtection dataChannelProtection = FtpsDataChannelProtection.PRIVATE;

    /**
     * 是否信任所有证书（仅限开发自签名联调环境，生产环境严禁开启）
     */
    private boolean trustAll = false;

    /**
     * 自定义 TrustStore 信任证书库文件路径（支持 classpath: 或绝对路径）
     */
    private String trustStorePath;

    /**
     * TrustStore 密码
     */
    private String trustStorePassword;

    /**
     * KeyStore 客户端证书库文件路径（双向认证时配置）
     */
    private String keyStorePath;

    /**
     * KeyStore 密码
     */
    private String keyStorePassword;
}
