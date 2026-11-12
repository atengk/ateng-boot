package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 支付报文加解密与验签响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCryptoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 交易流水号
     */
    private String tradeNo;

    /**
     * AES/GCM 认证密文 (Base64)
     */
    private String gcmCiphertext;

    /**
     * AES/GCM 认证解密验证明文
     */
    private String gcmDecrypted;

    /**
     * AES/CBC 对称密文 (Base64)
     */
    private String cbcCiphertext;

    /**
     * AES/CBC 对称解密验证明文
     */
    private String cbcDecrypted;

    /**
     * 国密 SM4/CBC 对称密文 (Base64)
     */
    private String sm4CbcCiphertext;

    /**
     * 国密 SM4/CBC 对称解密还原明文回执
     */
    private String sm4CbcDecrypted;

    /**
     * HMAC-SHA256 签名 (Hex)
     */
    private String hmacSha256Hex;

    /**
     * HMAC-SHA256 签名 (Base64)
     */
    private String hmacSha256Base64;

    /**
     * 恒定时间防侧信道比对验签结果
     */
    private boolean signatureVerified;

    /**
     * SHA-256 报文摘要
     */
    private String sha256Hex;

    /**
     * 国密 SM3 256-bit 报文摘要 (64 位十六进制)
     */
    private String sm3Hex;

    /**
     * MD5 报文摘要
     */
    private String md5Hex;

    /**
     * HexUtil 十六进制编码结果
     */
    private String encodedHex;

    /**
     * CRC32 报文校验和
     */
    private String crc32Hex;

    /**
     * CRC32C 硬件加速校验和
     */
    private String crc32cHex;

    /**
     * Adler32 报文校验和
     */
    private String adler32Hex;
}
