package io.github.atengk.boot.web.crypto.model;

import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * API 传输层加解密单次执行上下文契约
 *
 * @author Ateng
 * @since 2026-11-27
 */
public final class ApiCryptoContext {

    private final CryptoAlgorithm algorithm;
    private final String appId;
    private final String keyId;
    private final String key;
    private final byte[] aad;

    public ApiCryptoContext(CryptoAlgorithm algorithm, String appId, String keyId, String key, byte[] aad) {
        this.algorithm = Objects.requireNonNull(algorithm, "CryptoAlgorithm must not be null");
        this.appId = appId != null ? appId : "";
        this.keyId = keyId != null ? keyId : "";
        this.key = key;
        this.aad = aad != null ? aad.clone() : null;
    }

    public CryptoAlgorithm getAlgorithm() {
        return algorithm;
    }

    public String getAppId() {
        return appId;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getKey() {
        return key;
    }

    public byte[] getAad() {
        return aad != null ? aad.clone() : null;
    }

    /**
     * 按统一规范拼装 AEAD 附加认证数据 AAD（{@code appId|keyId|algorithm}）
     *
     * @param appId     应用标识
     * @param keyId     密钥版本标识
     * @param algorithm 加解密算法
     * @return UTF-8 编码的 AAD 字节数组
     */
    public static byte[] buildStandardAad(String appId, String keyId, CryptoAlgorithm algorithm) {
        String safeAppId = appId != null ? appId : "";
        String safeKeyId = keyId != null ? keyId : "";
        String safeAlg = algorithm != null ? algorithm.name() : "";
        return (safeAppId + "|" + safeKeyId + "|" + safeAlg).getBytes(StandardCharsets.UTF_8);
    }
}
