package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 纯 JDK 原生国密 SM4 解密器 (GB/T 32907-2016)
 * 支持纯原生 SM4/CBC 与 SM4/ECB，免 Bouncy Castle 外部依赖
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class Sm4ConfigDecryptor implements ConfigDecryptor {

    public static final int IV_SIZE = 16;
    public static final int KEY_SIZE = 16;

    @Override
    public boolean supports(EncryptAlgorithm algorithm) {
        return algorithm == EncryptAlgorithm.SM4_CBC || algorithm == EncryptAlgorithm.SM4_ECB;
    }

    @Override
    public String decrypt(String ciphertext, String password) throws Exception {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            return ciphertext;
        }
        byte[] combined = Base64.getDecoder().decode(ciphertext.trim());
        byte[] keyBytes = KeyDerivationHelper.deriveKey(password, KEY_SIZE);
        try {
            // CBC 模式需提取 16 字节前置 IV
            if (combined.length > IV_SIZE) {
                byte[] iv = new byte[IV_SIZE];
                System.arraycopy(combined, 0, iv, 0, IV_SIZE);
                int cipherLen = combined.length - IV_SIZE;
                byte[] encryptedBytes = new byte[cipherLen];
                System.arraycopy(combined, IV_SIZE, encryptedBytes, 0, cipherLen);
                try {
                    byte[] decrypted = CryptoUtil.decryptSm4Cbc(encryptedBytes, keyBytes, iv);
                    return new String(decrypted, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // 若 CBC 解密失败，尝试降级回退至纯 ECB 尝试（容错历史无 IV 密文）
                    byte[] decrypted = CryptoUtil.decryptSm4Ecb(combined, keyBytes);
                    return new String(decrypted, StandardCharsets.UTF_8);
                }
            } else {
                byte[] decrypted = CryptoUtil.decryptSm4Ecb(combined, keyBytes);
                return new String(decrypted, StandardCharsets.UTF_8);
            }
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /**
     * 静态辅助 SM4/CBC 加密方法（供工具链与单测使用）
     *
     * @param plaintext 明文字符串
     * @param password  主密钥口令
     * @return 包含 16 字节随机 IV 的 Base64 密文
     */
    public static String encryptCbc(String plaintext, String password) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = CryptoUtil.generateIv(IV_SIZE);
        byte[] keyBytes = KeyDerivationHelper.deriveKey(password, KEY_SIZE);
        try {
            byte[] encrypted = CryptoUtil.encryptSm4Cbc(plaintext.getBytes(StandardCharsets.UTF_8), keyBytes, iv);
            byte[] combined = new byte[IV_SIZE + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_SIZE);
            System.arraycopy(encrypted, 0, combined, IV_SIZE, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /**
     * 静态辅助 SM4/ECB 加密方法
     *
     * @param plaintext 明文字符串
     * @param password  主密钥口令
     * @return Base64 密文
     */
    public static String encryptEcb(String plaintext, String password) {
        if (plaintext == null) {
            return null;
        }
        byte[] keyBytes = KeyDerivationHelper.deriveKey(password, KEY_SIZE);
        try {
            byte[] encrypted = CryptoUtil.encryptSm4Ecb(plaintext.getBytes(StandardCharsets.UTF_8), keyBytes);
            return Base64.getEncoder().encodeToString(encrypted);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }
}
