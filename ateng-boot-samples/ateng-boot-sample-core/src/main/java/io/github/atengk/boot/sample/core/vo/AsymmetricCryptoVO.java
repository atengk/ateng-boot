package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 纯 JDK 原生 RSA-OAEP / RSASSA-PSS 与国密 SM2 非对称加解密及签验响应视图对象
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsymmetricCryptoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * X.509 PEM 公钥与 PKCS#8 PEM 私钥往返解析一致性校验
     */
    private boolean rsaKeyPemRoundTripValid;

    /**
     * RSA-OAEP-SHA256 (MGF1-SHA256) Base64 密文
     */
    private String rsaOaepCiphertext;

    /**
     * RSA-OAEP-SHA256 解密还原明文
     */
    private String rsaOaepDecrypted;

    /**
     * SHA256withRSA 签验是否通过
     */
    private boolean rsaSha256Verified;

    /**
     * RSASSA-PSS (MGF1-SHA256, saltLen=32) 签验是否通过
     */
    private boolean rsaPssVerified;

    /**
     * RSA 错钥解密与篡改报文验签是否被可靠阻断
     */
    private boolean rsaTamperBlocked;

    /**
     * 国密 SM2 130 位非压缩十六进制公钥（04 || X || Y）
     */
    private String sm2PublicKeyHex;

    /**
     * 国密 SM2 C1C3C2 标准 Base64 密文
     */
    private String sm2C1c3c2Ciphertext;

    /**
     * 国密 SM2 ASN.1 DER Base64 密文
     */
    private String sm2DerCiphertext;

    /**
     * 国密 SM2 C1C3C2 解密还原明文
     */
    private String sm2C1c3c2Decrypted;

    /**
     * 国密 SM2 ASN.1 DER 解密还原明文
     */
    private String sm2DerDecrypted;

    /**
     * 国密 SM2/SM3 ASN.1 DER 签验是否通过
     */
    private boolean sm2DerVerified;

    /**
     * 国密 SM2/SM3 裸 64 字节签名验签是否通过
     */
    private boolean sm2RawVerified;

    /**
     * 自定义 SM2 userId 域隔离是否生效（跨 userId 验签返回 false）
     */
    private boolean sm2UserIdIsolated;

    /**
     * SM2 C3 篡改密文解密是否触发脱敏异常拦截
     */
    private boolean sm2TamperBlocked;
}
