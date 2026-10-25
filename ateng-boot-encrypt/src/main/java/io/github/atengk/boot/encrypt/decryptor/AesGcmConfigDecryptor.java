package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 纯 JDK 原生 AES-GCM 带认证解密器
 * 默认采用 12 字节随机 IV 前置拼装，末尾自带 128 位 GCM 认证 Tag，具备极高抗篡改性
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class AesGcmConfigDecryptor implements ConfigDecryptor {

    public static final int IV_SIZE = 12;
    public static final int KEY_SIZE = 32;

    @Override
    public boolean supports(EncryptAlgorithm algorithm) {
        return algorithm == EncryptAlgorithm.AES_GCM;
    }

    @Override
    public String decrypt(String ciphertext, String password) throws Exception {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            return ciphertext;
        }
        byte[] combined = Base64.getDecoder().decode(ciphertext.trim());
        if (combined.length <= IV_SIZE) {
            throw new IllegalArgumentException("AES-GCM 密文长度非法，不足以包含 12 字节前置 IV");
        }

        byte[] iv = new byte[IV_SIZE];
        System.arraycopy(combined, 0, iv, 0, IV_SIZE);

        int cipherLen = combined.length - IV_SIZE;
        byte[] encryptedBytes = new byte[cipherLen];
        System.arraycopy(combined, IV_SIZE, encryptedBytes, 0, cipherLen);

        byte[] keyBytes = KeyDerivationHelper.deriveKey(password, KEY_SIZE);
        try {
            byte[] decrypted = CryptoUtil.decryptGcm(encryptedBytes, keyBytes, iv);
            return new String(decrypted, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /**
     * 静态辅助加密方法（供工具链与单测使用）
     *
     * @param plaintext 明文字符串
     * @param password  主密钥口令
     * @return 包含 12 字节随机 IV 的 Base64 密文
     */
    public static String encrypt(String plaintext, String password) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = CryptoUtil.generateIv(IV_SIZE);
        byte[] keyBytes = KeyDerivationHelper.deriveKey(password, KEY_SIZE);
        try {
            byte[] encrypted = CryptoUtil.encryptGcm(plaintext.getBytes(StandardCharsets.UTF_8), keyBytes, iv);
            byte[] combined = new byte[IV_SIZE + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_SIZE);
            System.arraycopy(encrypted, 0, combined, IV_SIZE, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }
}
