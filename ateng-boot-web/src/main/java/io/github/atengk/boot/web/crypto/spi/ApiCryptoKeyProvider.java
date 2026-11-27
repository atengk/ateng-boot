package io.github.atengk.boot.web.crypto.spi;

import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;

/**
 * API 传输层加解密非对称密钥提供者 SPI 契约
 * 支持按应用标识（appId）、密钥版本（keyId）与算法路由提取服务端私钥与客户端公钥，支撑平滑密钥轮换
 *
 * @author Ateng
 * @since 2026-11-27
 */
public interface ApiCryptoKeyProvider {

    /**
     * 获取服务端私钥（用于入站请求体解封会话密钥）
     * 当指定的 {@code keyId} 未知或已停用/过期时，实现方应主动抛出语义化 {@code BusinessException(400, ...)}
     *
     * @param appId     应用标识
     * @param keyId     密钥版本标识
     * @param algorithm 加解密算法
     * @return 服务端私钥（RSA PKCS#8 或 SM2 Hex/Base64/PEM）
     */
    String getServerPrivateKey(String appId, String keyId, CryptoAlgorithm algorithm);

    /**
     * 获取客户端公钥（用于出站响应体封装会话密钥）
     *
     * @param appId     应用标识
     * @param keyId     密钥版本标识
     * @param algorithm 加解密算法
     * @return 客户端公钥（RSA X.509 或 SM2 Hex/Base64/PEM）
     */
    String getClientPublicKey(String appId, String keyId, CryptoAlgorithm algorithm);

    /**
     * 获取出站加密当前应使用的活跃密钥版本标识（默认 "v1"）
     *
     * @param appId     应用标识
     * @param algorithm 加解密算法
     * @return 活跃 keyId，将回写至响应头 {@code X-Key-Id}
     */
    default String getActiveKeyId(String appId, CryptoAlgorithm algorithm) {
        return "v1";
    }
}
