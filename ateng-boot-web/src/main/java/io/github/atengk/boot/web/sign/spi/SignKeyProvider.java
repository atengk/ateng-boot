package io.github.atengk.boot.web.sign.spi;

/**
 * 签名密钥提供者 SPI 契约接口
 * 支持多商户、多应用场景下动态按 appId 提取对称或非对称密钥
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface SignKeyProvider {

    /**
     * 获取指定应用的对称密钥 (SecretKey)
     *
     * @param appId 应用/商户唯一标识
     * @return 对称密钥明文
     */
    String getSecretKey(String appId);

    /**
     * 获取指定应用的公钥 (PublicKey，用于验签客户端入站请求)
     *
     * @param appId 应用/商户唯一标识
     * @return 公钥 Base64 字符串
     */
    String getPublicKey(String appId);

    /**
     * 获取指定应用的私钥 (PrivateKey，用于服务端出站加签)
     *
     * @param appId 应用/商户唯一标识
     * @return 私钥 Base64 字符串
     */
    String getPrivateKey(String appId);
}
