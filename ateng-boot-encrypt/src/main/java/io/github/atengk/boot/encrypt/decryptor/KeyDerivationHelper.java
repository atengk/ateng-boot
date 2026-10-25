package io.github.atengk.boot.encrypt.decryptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 密钥派生与规范化辅助工具
 * 将任意长度的字符串口令安全规范化为密码学标准长度（16 / 24 / 32 字节）密钥
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class KeyDerivationHelper {

    private KeyDerivationHelper() {
    }

    /**
     * 将口令派生为指定字节长度的密钥
     *
     * @param password       用户口令
     * @param requiredLength 目标长度（通常为 16 字节或 32 字节）
     * @return 派生后的密钥字节数组
     */
    public static byte[] deriveKey(String password, int requiredLength) {
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("配置解密主密钥不能为空");
        }
        byte[] raw = password.getBytes(StandardCharsets.UTF_8);
        if (raw.length == requiredLength) {
            byte[] result = new byte[requiredLength];
            System.arraycopy(raw, 0, result, 0, requiredLength);
            java.util.Arrays.fill(raw, (byte) 0);
            return result;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw);
            if (requiredLength == hash.length) {
                return hash;
            }
            byte[] result = new byte[requiredLength];
            System.arraycopy(hash, 0, result, 0, Math.min(hash.length, requiredLength));
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法在当前 JVM 中不可用", e);
        } finally {
            java.util.Arrays.fill(raw, (byte) 0);
        }
    }
}
