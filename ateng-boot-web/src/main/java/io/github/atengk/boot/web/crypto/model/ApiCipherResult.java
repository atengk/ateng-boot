package io.github.atengk.boot.web.crypto.model;

/**
 * API 传输层加密输出结果不可变载体
 *
 * @param encryptedKey Base64 编码的非对称封装会话密钥（写入 X-Encrypt-Key）
 * @param iv           Base64 编码的 12 字节随机 GCM IV（写入 X-Encrypt-Iv）
 * @param cipherText   Base64 编码的对称认证密文（写入 JSON 信封 cipherText 字段）
 * @author Ateng
 * @since 2026-11-27
 */
public record ApiCipherResult(String encryptedKey, String iv, String cipherText) {
}
