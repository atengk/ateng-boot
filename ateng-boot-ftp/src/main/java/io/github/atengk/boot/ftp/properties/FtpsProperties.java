package io.github.atengk.boot.ftp.properties;

import io.github.atengk.boot.ftp.enums.FtpsDataChannelProtection;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.HashSet;
import java.util.Set;

/**
 * FTPS 安全加密传输配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Getter
@EqualsAndHashCode
@ToString
public class FtpsProperties {

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final Set<String> configuredFields = new HashSet<>();

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

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.configuredFields.add("enabled");
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
        this.configuredFields.add("protocol");
    }

    public void setImplicit(boolean implicit) {
        this.implicit = implicit;
        this.configuredFields.add("implicit");
    }

    public void setDataChannelProtection(FtpsDataChannelProtection dataChannelProtection) {
        this.dataChannelProtection = dataChannelProtection;
        this.configuredFields.add("dataChannelProtection");
    }

    public void setTrustAll(boolean trustAll) {
        this.trustAll = trustAll;
        this.configuredFields.add("trustAll");
    }

    public void setTrustStorePath(String trustStorePath) {
        this.trustStorePath = trustStorePath;
        this.configuredFields.add("trustStorePath");
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
        this.configuredFields.add("trustStorePassword");
    }

    public void setKeyStorePath(String keyStorePath) {
        this.keyStorePath = keyStorePath;
        this.configuredFields.add("keyStorePath");
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
        this.configuredFields.add("keyStorePassword");
    }

    /**
     * 创建当前 FTPS 配置的深拷贝（隔离可变状态）
     *
     * @return 独立拷贝实例
     */
    public FtpsProperties copy() {
        FtpsProperties copy = new FtpsProperties();
        copy.enabled = this.enabled;
        copy.protocol = this.protocol;
        copy.implicit = this.implicit;
        copy.dataChannelProtection = this.dataChannelProtection;
        copy.trustAll = this.trustAll;
        copy.trustStorePath = this.trustStorePath;
        copy.trustStorePassword = this.trustStorePassword;
        copy.keyStorePath = this.keyStorePath;
        copy.keyStorePassword = this.keyStorePassword;
        copy.configuredFields.addAll(this.configuredFields);
        return copy;
    }

    /**
     * 从父级默认 FTPS 配置继承当前实例未显式设置的属性（绝不覆盖显式配置）
     *
     * @param defaults 父级默认 FTPS 配置
     */
    public void mergeFrom(FtpsProperties defaults) {
        if (defaults == null) {
            return;
        }
        this.enabled = pick("enabled", this.enabled, defaults.enabled);
        this.protocol = pick("protocol", this.protocol, defaults.protocol);
        this.implicit = pick("implicit", this.implicit, defaults.implicit);
        this.dataChannelProtection = pick("dataChannelProtection", this.dataChannelProtection, defaults.dataChannelProtection);
        this.trustAll = pick("trustAll", this.trustAll, defaults.trustAll);
        this.trustStorePath = pick("trustStorePath", this.trustStorePath, defaults.trustStorePath);
        this.trustStorePassword = pick("trustStorePassword", this.trustStorePassword, defaults.trustStorePassword);
        this.keyStorePath = pick("keyStorePath", this.keyStorePath, defaults.keyStorePath);
        this.keyStorePassword = pick("keyStorePassword", this.keyStorePassword, defaults.keyStorePassword);
    }

    private <T> T pick(String key, T current, T parent) {
        return configuredFields.contains(key) ? current : parent;
    }
}
