package io.github.atengk.boot.web.sign.provider;

import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.spi.SignKeyProvider;

import java.util.Objects;

/**
 * 默认签名密钥提供者实现
 * 基于 WebProperties.SignProperties 读取全局预配的对称密钥、RSA 公钥或 RSA 私钥，支撑单商户开箱即用
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class DefaultSignKeyProvider implements SignKeyProvider {

    private final WebProperties.SignProperties properties;

    public DefaultSignKeyProvider(WebProperties.SignProperties properties) {
        this.properties = Objects.requireNonNull(properties, "SignProperties must not be null");
    }

    @Override
    public String getSecretKey(String appId) {
        return properties.getSecretKey();
    }

    @Override
    public String getPublicKey(String appId) {
        return properties.getPublicKey();
    }

    @Override
    public String getPrivateKey(String appId) {
        return properties.getPrivateKey();
    }
}
