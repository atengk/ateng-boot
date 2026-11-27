package io.github.atengk.boot.web.crypto.provider;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.spi.ApiCryptoKeyProvider;
import io.github.atengk.boot.web.properties.WebProperties;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;

/**
 * 默认 API 传输层加解密密钥提供者实现
 * 基于 {@link WebProperties.CryptoProperties} 加载全局默认密钥及 {@code keys.<keyId>} 多版本密钥表，
 * 支持单应用开箱即用、RSA/SM2 多算法共存与多版本 {@code keyId} 平滑轮换及停用拦截
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class DefaultApiCryptoKeyProvider implements ApiCryptoKeyProvider {

    private final WebProperties.CryptoProperties properties;

    public DefaultApiCryptoKeyProvider(WebProperties.CryptoProperties properties) {
        this.properties = Objects.requireNonNull(properties, "CryptoProperties must not be null");
    }

    @Override
    public String getServerPrivateKey(String appId, String keyId, CryptoAlgorithm algorithm) {
        WebProperties.CryptoKeyVersionProperties version = resolveAndValidateVersion(keyId);
        if (version != null) {
            String key = extractServerPrivateKey(version, algorithm);
            if (StringUtils.hasText(key)) {
                return key;
            }
            if (StringUtils.hasText(properties.getActiveKeyId())
                    && properties.getActiveKeyId().equals(resolveEffectiveKeyId(keyId))) {
                return extractTopLevelServerPrivateKey(algorithm);
            }
            return null;
        }
        return extractTopLevelServerPrivateKey(algorithm);
    }

    @Override
    public String getClientPublicKey(String appId, String keyId, CryptoAlgorithm algorithm) {
        WebProperties.CryptoKeyVersionProperties version = resolveAndValidateVersion(keyId);
        if (version != null) {
            String key = extractClientPublicKey(version, algorithm);
            if (StringUtils.hasText(key)) {
                return key;
            }
            if (StringUtils.hasText(properties.getActiveKeyId())
                    && properties.getActiveKeyId().equals(resolveEffectiveKeyId(keyId))) {
                return extractTopLevelClientPublicKey(algorithm);
            }
            return null;
        }
        return extractTopLevelClientPublicKey(algorithm);
    }

    @Override
    public String getActiveKeyId(String appId, CryptoAlgorithm algorithm) {
        return StringUtils.hasText(properties.getActiveKeyId()) ? properties.getActiveKeyId().trim() : "v1";
    }

    private WebProperties.CryptoKeyVersionProperties resolveAndValidateVersion(String keyId) {
        String effectiveKeyId = resolveEffectiveKeyId(keyId);
        Map<String, WebProperties.CryptoKeyVersionProperties> keys = properties.getKeys();
        if (keys != null && !keys.isEmpty()) {
            WebProperties.CryptoKeyVersionProperties version = keys.get(effectiveKeyId);
            if (version == null) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "未知的加密密钥版本: " + effectiveKeyId);
            }
            if (!version.isEnabled()) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "加密密钥版本已过期或停用: " + effectiveKeyId);
            }
            return version;
        }
        String activeKeyId = properties.getActiveKeyId();
        if (StringUtils.hasText(keyId) && StringUtils.hasText(activeKeyId) && !activeKeyId.trim().equals(keyId.trim())) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "未知或已过期的加密密钥版本: " + keyId);
        }
        return null;
    }

    private String resolveEffectiveKeyId(String keyId) {
        if (StringUtils.hasText(keyId)) {
            return keyId.trim();
        }
        return getActiveKeyId(properties.getAppId(), properties.getAlgorithm());
    }

    private String extractTopLevelServerPrivateKey(CryptoAlgorithm algorithm) {
        if (algorithm == CryptoAlgorithm.RSA_AES_GCM && StringUtils.hasText(properties.getRsaServerPrivateKey())) {
            return properties.getRsaServerPrivateKey();
        }
        if (algorithm == CryptoAlgorithm.SM2_SM4_GCM && StringUtils.hasText(properties.getSm2ServerPrivateKey())) {
            return properties.getSm2ServerPrivateKey();
        }
        return properties.getServerPrivateKey();
    }

    private String extractTopLevelClientPublicKey(CryptoAlgorithm algorithm) {
        if (algorithm == CryptoAlgorithm.RSA_AES_GCM && StringUtils.hasText(properties.getRsaClientPublicKey())) {
            return properties.getRsaClientPublicKey();
        }
        if (algorithm == CryptoAlgorithm.SM2_SM4_GCM && StringUtils.hasText(properties.getSm2ClientPublicKey())) {
            return properties.getSm2ClientPublicKey();
        }
        return properties.getClientPublicKey();
    }

    private String extractServerPrivateKey(WebProperties.CryptoKeyVersionProperties version, CryptoAlgorithm algorithm) {
        if (algorithm == CryptoAlgorithm.RSA_AES_GCM && StringUtils.hasText(version.getRsaServerPrivateKey())) {
            return version.getRsaServerPrivateKey();
        }
        if (algorithm == CryptoAlgorithm.SM2_SM4_GCM && StringUtils.hasText(version.getSm2ServerPrivateKey())) {
            return version.getSm2ServerPrivateKey();
        }
        return version.getServerPrivateKey();
    }

    private String extractClientPublicKey(WebProperties.CryptoKeyVersionProperties version, CryptoAlgorithm algorithm) {
        if (algorithm == CryptoAlgorithm.RSA_AES_GCM && StringUtils.hasText(version.getRsaClientPublicKey())) {
            return version.getRsaClientPublicKey();
        }
        if (algorithm == CryptoAlgorithm.SM2_SM4_GCM && StringUtils.hasText(version.getSm2ClientPublicKey())) {
            return version.getSm2ClientPublicKey();
        }
        return version.getClientPublicKey();
    }
}
