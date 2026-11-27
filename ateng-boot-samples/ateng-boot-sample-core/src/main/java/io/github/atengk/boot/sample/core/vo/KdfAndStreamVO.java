package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RFC 5869 HKDF 密钥派生、HMAC-SHA512、流式摘要与自包含 Packed AEAD 响应视图对象
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KdfAndStreamVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * HKDF-SHA256 派生的 32 字节（64 Hex）子密钥摘要展示
     */
    private String hkdfSha256KeyHex;

    /**
     * 国密 HKDF-SM3 派生的 32 字节（64 Hex）子密钥摘要展示
     */
    private String hkdfSm3KeyHex;

    /**
     * 不同 info 上下文派生的子密钥是否严格域隔离
     */
    private boolean hkdfDomainIsolated;

    /**
     * HMAC-SHA512 小写十六进制认证标签（128 位 Hex 字符 / 64 字节）
     */
    private String hmacSha512Hex;

    /**
     * HMAC-SHA512 恒定时间防侧信道验签是否通过
     */
    private boolean hmacSha512Verified;

    /**
     * SHA-256 流式 InputStream 摘要（Hex）
     */
    private String sha256StreamHex;

    /**
     * SHA-512 流式 InputStream 摘要（Hex）
     */
    private String sha512StreamHex;

    /**
     * 国密 SM3 流式 InputStream 摘要（Hex）
     */
    private String sm3StreamHex;

    /**
     * 自包含 AES-GCM Packed (12B IV || 密文 || 16B Tag) Base64 密文
     */
    private String aesGcmPackedCiphertext;

    /**
     * AES-GCM Packed 带 AAD 解密还原明文
     */
    private String aesGcmPackedDecrypted;

    /**
     * 国密 SM4-GCM Packed Base64 密文
     */
    private String sm4GcmPackedCiphertext;

    /**
     * 国密 SM4-GCM Packed 带 AAD 解密还原明文
     */
    private String sm4GcmPackedDecrypted;

    /**
     * 无填充 Base64URL 安全令牌编码结果
     */
    private String base64UrlToken;

    /**
     * Base64URL 解码还原明文
     */
    private String base64UrlDecoded;

    /**
     * CryptoUtil.constantTimeEquals 同值与异值比对校验结果
     */
    private boolean constantTimeEqualsValid;
}
