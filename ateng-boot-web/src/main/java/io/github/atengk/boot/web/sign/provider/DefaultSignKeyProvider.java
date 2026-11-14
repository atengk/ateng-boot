package io.github.atengk.boot.web.sign.provider;

import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import io.github.atengk.boot.web.sign.spi.SignKeyProvider;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 默认签名密钥提供者实现
 * 基于 WebProperties.SignProperties 读取全局预配或分算法配置的对称密钥、RSA 公私钥及 SM2 公私钥，支撑单/多算法开箱即用
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

    @Override
    public String getVerifyKey(String appId, SignAlgorithm algorithm) {
        if (algorithm == SignAlgorithm.RSA && StringUtils.hasText(properties.getRsaPublicKey())) {
            return properties.getRsaPublicKey();
        }
        if (algorithm == SignAlgorithm.SM2 && StringUtils.hasText(properties.getSm2PublicKey())) {
            return properties.getSm2PublicKey();
        }
        return SignKeyProvider.super.getVerifyKey(appId, algorithm);
    }

    @Override
    public String getSignKey(String appId, SignAlgorithm algorithm) {
        if (algorithm == SignAlgorithm.RSA && StringUtils.hasText(properties.getRsaPrivateKey())) {
            return properties.getRsaPrivateKey();
        }
        if (algorithm == SignAlgorithm.SM2 && StringUtils.hasText(properties.getSm2PrivateKey())) {
            return properties.getSm2PrivateKey();
        }
        return SignKeyProvider.super.getSignKey(appId, algorithm);
    }
}
