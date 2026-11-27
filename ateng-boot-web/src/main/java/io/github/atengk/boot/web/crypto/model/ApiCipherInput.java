package io.github.atengk.boot.web.crypto.model;

/**
 * API 传输层解密输入参数不可变载体
 *
 * @param encryptedKey Base64 编码的非对称加密会话密钥
 * @param iv           Base64 编码的 12 字节随机 GCM IV
 * @param cipherText   Base64 编码的对称认证密文（含末尾 16 字节认证 Tag）
 * @author Ateng
 * @since 2026-11-27
 */
public record ApiCipherInput(String encryptedKey, String iv, String cipherText) {
}
