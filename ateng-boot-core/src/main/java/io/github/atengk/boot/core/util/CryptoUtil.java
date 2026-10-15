package io.github.atengk.boot.core.util;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 纯 JDK 原生对称加密工具类
 * 基于 Zero-Bloat 原则，100% 依托 JDK 原生能力实现 AES/CBC 与 AES/GCM 安全加解密，无 Bouncy Castle 外部依赖
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class CryptoUtil {

    public static final String ALGORITHM_AES = "AES";
    public static final String TRANSFORMATION_AES_CBC = "AES/CBC/PKCS5Padding";
    public static final String TRANSFORMATION_AES_GCM = "AES/GCM/NoPadding";

    public static final int GCM_TAG_LENGTH = 128;
    public static final int DEFAULT_CBC_IV_SIZE = 16;
    public static final int DEFAULT_GCM_IV_SIZE = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    /**
     * 生成指定位长度的 AES 随机密钥
     *
     * @param bitLength 密钥位长（128, 192 或 256）
     * @return 密钥字节数组
     */
    public static byte[] generateKey(int bitLength) {
        if (bitLength != 128 && bitLength != 192 && bitLength != 256) {
            throw new IllegalArgumentException("AES 密钥长度必须为 128, 192 或 256 位，实际入参: " + bitLength);
        }
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM_AES);
            keyGen.init(bitLength, SECURE_RANDOM);
            SecretKey secretKey = keyGen.generateKey();
            return secretKey.getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 环境不支持 AES 算法", e);
        }
    }

    /**
     * 生成安全随机初始向量（IV）
     *
     * @param byteLength IV 字节长度（CBC 通常为 16 字节，GCM 推荐 12 字节）
     * @return 随机 IV 字节数组
     */
    public static byte[] generateIv(int byteLength) {
        if (byteLength <= 0) {
            throw new IllegalArgumentException("IV 字节长度必须大于 0，实际入参: " + byteLength);
        }
        byte[] iv = new byte[byteLength];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    /**
     * 生成 CBC 模式推荐的 16 字节随机初始向量（IV）
     *
     * @return 16 字节 IV 数组
     */
    public static byte[] generateIv() {
        return generateIv(DEFAULT_CBC_IV_SIZE);
    }

    /**
     * 生成 GCM 模式推荐的 12 字节随机初始向量（IV）
     *
     * @return 12 字节 IV 数组
     */
    public static byte[] generateGcmIv() {
        return generateIv(DEFAULT_GCM_IV_SIZE);
    }

    /**
     * 对字节数组进行标准 Base64 编码
     *
     * @param data 原始字节数组
     * @return Base64 字符串；入参为 null 时返回 null
     */
    public static String encodeBase64(byte[] data) {
        if (data == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * 对 Base64 字符串进行解码
     *
     * @param base64Str Base64 编码字符串
     * @return 原始字节数组；入参为 null 时返回 null
     */
    public static byte[] decodeBase64(String base64Str) {
        if (base64Str == null) {
            return null;
        }
        return Base64.getDecoder().decode(base64Str);
    }

    // ==================== AES/CBC/PKCS5Padding ====================

    /**
     * AES/CBC/PKCS5Padding 加密（字节数组）
     *
     * @param plaintext 明文字节数组
     * @param key       AES 密钥（16/24/32 字节）
     * @param iv        初始向量（必须为 16 字节）
     * @return 密文字节数组；明文为 null 时返回 null
     */
    public static byte[] encryptCbc(byte[] plaintext, byte[] key, byte[] iv) {
        if (plaintext == null) {
            return null;
        }
        validateKeyAndIv(key, iv, DEFAULT_CBC_IV_SIZE);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_AES_CBC);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM_AES);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("AES/CBC 加密执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * AES/CBC/PKCS5Padding 解密（字节数组）
     *
     * @param ciphertext 密文字节数组
     * @param key        AES 密钥（16/24/32 字节）
     * @param iv         初始向量（必须为 16 字节）
     * @return 明文字节数组；密文为 null 时返回 null
     */
    public static byte[] decryptCbc(byte[] ciphertext, byte[] key, byte[] iv) {
        if (ciphertext == null) {
            return null;
        }
        validateKeyAndIv(key, iv, DEFAULT_CBC_IV_SIZE);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_AES_CBC);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM_AES);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("AES/CBC 解密执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * AES/CBC/PKCS5Padding 加密字符串，返回 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       AES 密钥（16/24/32 字节）
     * @param iv        初始向量（必须为 16 字节）
     * @return Base64 格式密文字符串；明文为 null 时返回 null
     */
    public static String encryptCbc(String plaintext, byte[] key, byte[] iv) {
        if (plaintext == null) {
            return null;
        }
        byte[] encrypted = encryptCbc(plaintext.getBytes(StandardCharsets.UTF_8), key, iv);
        return encodeBase64(encrypted);
    }

    /**
     * AES/CBC/PKCS5Padding 解密 Base64 密文，返回明文字符串
     *
     * @param base64Ciphertext Base64 编码密文
     * @param key              AES 密钥（16/24/32 字节）
     * @param iv               初始向量（必须为 16 字节）
     * @return UTF-8 明文字符串；密文为 null 时返回 null
     */
    public static String decryptCbc(String base64Ciphertext, byte[] key, byte[] iv) {
        if (base64Ciphertext == null) {
            return null;
        }
        byte[] decrypted = decryptCbc(decodeBase64(base64Ciphertext), key, iv);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    // ==================== AES/GCM/NoPadding ====================

    /**
     * AES/GCM/NoPadding 加密（带可选 AAD 附加认证数据）
     *
     * @param plaintext 明文字节数组
     * @param key       AES 密钥（16/24/32 字节）
     * @param iv        初始向量（推荐 12 字节）
     * @param aad       附加认证数据（可为 null）
     * @return 包含认证 Tag 的密文字节数组；明文为 null 时返回 null
     */
    public static byte[] encryptGcm(byte[] plaintext, byte[] key, byte[] iv, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        validateKeyAndIv(key, iv, -1);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_AES_GCM);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM_AES);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("AES/GCM 加密执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * AES/GCM/NoPadding 加密
     *
     * @param plaintext 明文字节数组
     * @param key       AES 密钥（16/24/32 字节）
     * @param iv        初始向量（推荐 12 字节）
     * @return 密文字节数组
     */
    public static byte[] encryptGcm(byte[] plaintext, byte[] key, byte[] iv) {
        return encryptGcm(plaintext, key, iv, null);
    }

    /**
     * AES/GCM/NoPadding 解密（带可选 AAD 附加认证数据验证）
     *
     * @param ciphertext 密文字节数组（末尾附带认证 Tag）
     * @param key        AES 密钥（16/24/32 字节）
     * @param iv         初始向量（与加密时一致）
     * @param aad        附加认证数据（与加密时一致，可为 null）
     * @return 明文字节数组；密文为 null 时返回 null
     */
    public static byte[] decryptGcm(byte[] ciphertext, byte[] key, byte[] iv, byte[] aad) {
        if (ciphertext == null) {
            return null;
        }
        validateKeyAndIv(key, iv, -1);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_AES_GCM);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM_AES);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("AES/GCM 解密或认证校验失败: " + e.getMessage(), e);
        }
    }

    /**
     * AES/GCM/NoPadding 解密
     *
     * @param ciphertext 密文字节数组
     * @param key        AES 密钥（16/24/32 字节）
     * @param iv         初始向量
     * @return 明文字节数组
     */
    public static byte[] decryptGcm(byte[] ciphertext, byte[] key, byte[] iv) {
        return decryptGcm(ciphertext, key, iv, null);
    }

    /**
     * AES/GCM/NoPadding 加密字符串，返回 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       AES 密钥（16/24/32 字节）
     * @param iv        初始向量（推荐 12 字节）
     * @return Base64 编码密文
     */
    public static String encryptGcm(String plaintext, byte[] key, byte[] iv) {
        if (plaintext == null) {
            return null;
        }
        byte[] encrypted = encryptGcm(plaintext.getBytes(StandardCharsets.UTF_8), key, iv);
        return encodeBase64(encrypted);
    }

    /**
     * AES/GCM/NoPadding 解密 Base64 密文，返回明文字符串
     *
     * @param base64Ciphertext Base64 编码密文
     * @param key              AES 密钥（16/24/32 字节）
     * @param iv               初始向量
     * @return UTF-8 明文字符串
     */
    public static String decryptGcm(String base64Ciphertext, byte[] key, byte[] iv) {
        if (base64Ciphertext == null) {
            return null;
        }
        byte[] decrypted = decryptGcm(decodeBase64(base64Ciphertext), key, iv);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private static void validateKeyAndIv(byte[] key, byte[] iv, int requiredIvLength) {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            int len = key != null ? key.length : 0;
            throw new IllegalArgumentException("AES 密钥字节长度必须为 16, 24 或 32（128/192/256 位），实际为: " + len);
        }
        if (iv == null || iv.length == 0) {
            throw new IllegalArgumentException("IV 初始向量不能为空");
        }
        if (requiredIvLength > 0 && iv.length != requiredIvLength) {
            throw new IllegalArgumentException("当前模式要求的 IV 字节长度必须为 " + requiredIvLength + "，实际为: " + iv.length);
        }
    }
}
