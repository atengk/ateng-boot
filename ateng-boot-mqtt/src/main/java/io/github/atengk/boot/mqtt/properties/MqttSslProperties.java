package io.github.atengk.boot.mqtt.properties;

/**
 * MQTT TLS/SSL 加密与双向 mTLS 证书配置属性
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class MqttSslProperties {

    /**
     * 是否启用 TLS/SSL 加密
     */
    private boolean enabled = false;

    /**
     * TLS 协议版本 (默认 TLSv1.3)
     */
    private String protocol = "TLSv1.3";

    /**
     * CA 根证书路径（PEM 格式或类路径文件）
     */
    private String caCert;

    /**
     * 客户端证书路径（PEM 格式，双向 mTLS 必需）
     */
    private String clientCert;

    /**
     * 客户端私钥路径（PEM 格式，双向 mTLS 必需）
     */
    private String clientKey;

    /**
     * 信任库文件路径 (JKS / PKCS12)
     */
    private String trustStore;

    /**
     * 信任库密码
     */
    private String trustStorePassword;

    /**
     * 密钥库文件路径 (JKS / PKCS12)
     */
    private String keyStore;

    /**
     * 密钥库密码
     */
    private String keyStorePassword;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getCaCert() {
        return caCert;
    }

    public void setCaCert(String caCert) {
        this.caCert = caCert;
    }

    public String getClientCert() {
        return clientCert;
    }

    public void setClientCert(String clientCert) {
        this.clientCert = clientCert;
    }

    public String getClientKey() {
        return clientKey;
    }

    public void setClientKey(String clientKey) {
        this.clientKey = clientKey;
    }

    public String getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(String trustStore) {
        this.trustStore = trustStore;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public void setKeyStore(String keyStore) {
        this.keyStore = keyStore;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }
}
