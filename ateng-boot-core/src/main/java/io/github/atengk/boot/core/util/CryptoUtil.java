package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 纯 JDK 原生对称加密与国密工具类
 * 基于 Zero-Bloat 原则，100% 依托 JDK 原生能力实现 AES/GCM、AES/CBC 以及纯原生国密 SM4/GCM、SM4/CBC、SM4/ECB 安全加解密，
 * 并提供 Base64URL 编解码、自包含封装版 AEAD（*Packed）与恒定时间比对原语，无 Bouncy Castle 等外部依赖
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

    public static final int SM4_KEY_SIZE = 16;
    public static final int SM4_BLOCK_SIZE = 16;
    public static final int SM4_IV_SIZE = 16;

    private static final int SM4_GCM_TAG_BYTES = GCM_TAG_LENGTH / 8;
    private static final int PACKED_GCM_MIN_BYTES = DEFAULT_GCM_IV_SIZE + SM4_GCM_TAG_BYTES;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    // ==================== 国密 SM4 算法常量 (GB/T 32907-2016) ====================

    private static final byte[] SM4_SBOX = new byte[]{
            (byte) 0xd6, (byte) 0x90, (byte) 0xe9, (byte) 0xfe, (byte) 0xcc, (byte) 0xe1, (byte) 0x3d, (byte) 0xb7,
            (byte) 0x16, (byte) 0xb6, (byte) 0x14, (byte) 0xc2, (byte) 0x28, (byte) 0xfb, (byte) 0x2c, (byte) 0x05,
            (byte) 0x2b, (byte) 0x67, (byte) 0x9a, (byte) 0x76, (byte) 0x2a, (byte) 0xbe, (byte) 0x04, (byte) 0xc3,
            (byte) 0xaa, (byte) 0x44, (byte) 0x13, (byte) 0x26, (byte) 0x49, (byte) 0x86, (byte) 0x06, (byte) 0x99,
            (byte) 0x9c, (byte) 0x42, (byte) 0x50, (byte) 0xf4, (byte) 0x91, (byte) 0xef, (byte) 0x98, (byte) 0x7a,
            (byte) 0x33, (byte) 0x54, (byte) 0x0b, (byte) 0x43, (byte) 0xed, (byte) 0xcf, (byte) 0xac, (byte) 0x62,
            (byte) 0xe4, (byte) 0xb3, (byte) 0x1c, (byte) 0xa9, (byte) 0xc9, (byte) 0x08, (byte) 0xe8, (byte) 0x95,
            (byte) 0x80, (byte) 0xdf, (byte) 0x94, (byte) 0xfa, (byte) 0x75, (byte) 0x8f, (byte) 0x3f, (byte) 0xa6,
            (byte) 0x47, (byte) 0x07, (byte) 0xa7, (byte) 0xfc, (byte) 0xf3, (byte) 0x73, (byte) 0x17, (byte) 0xba,
            (byte) 0x83, (byte) 0x59, (byte) 0x3c, (byte) 0x19, (byte) 0xe6, (byte) 0x85, (byte) 0x4f, (byte) 0xa8,
            (byte) 0x68, (byte) 0x6b, (byte) 0x81, (byte) 0xb2, (byte) 0x71, (byte) 0x64, (byte) 0xda, (byte) 0x8b,
            (byte) 0xf8, (byte) 0xeb, (byte) 0x0f, (byte) 0x4b, (byte) 0x70, (byte) 0x56, (byte) 0x9d, (byte) 0x35,
            (byte) 0x1e, (byte) 0x24, (byte) 0x0e, (byte) 0x5e, (byte) 0x63, (byte) 0x58, (byte) 0xd1, (byte) 0xa2,
            (byte) 0x25, (byte) 0x22, (byte) 0x7c, (byte) 0x3b, (byte) 0x01, (byte) 0x21, (byte) 0x78, (byte) 0x87,
            (byte) 0xd4, (byte) 0x00, (byte) 0x46, (byte) 0x57, (byte) 0x9f, (byte) 0xd3, (byte) 0x27, (byte) 0x52,
            (byte) 0x4c, (byte) 0x36, (byte) 0x02, (byte) 0xe7, (byte) 0xa0, (byte) 0xc4, (byte) 0xc8, (byte) 0x9e,
            (byte) 0xea, (byte) 0xbf, (byte) 0x8a, (byte) 0xd2, (byte) 0x40, (byte) 0xc7, (byte) 0x38, (byte) 0xb5,
            (byte) 0xa3, (byte) 0xf7, (byte) 0xf2, (byte) 0xce, (byte) 0xf9, (byte) 0x61, (byte) 0x15, (byte) 0xa1,
            (byte) 0xe0, (byte) 0xae, (byte) 0x5d, (byte) 0xa4, (byte) 0x9b, (byte) 0x34, (byte) 0x1a, (byte) 0x55,
            (byte) 0xad, (byte) 0x93, (byte) 0x32, (byte) 0x30, (byte) 0xf5, (byte) 0x8c, (byte) 0xb1, (byte) 0xe3,
            (byte) 0x1d, (byte) 0xf6, (byte) 0xe2, (byte) 0x2e, (byte) 0x82, (byte) 0x66, (byte) 0xca, (byte) 0x60,
            (byte) 0xc0, (byte) 0x29, (byte) 0x23, (byte) 0xab, (byte) 0x0d, (byte) 0x53, (byte) 0x4e, (byte) 0x6f,
            (byte) 0xd5, (byte) 0xdb, (byte) 0x37, (byte) 0x45, (byte) 0xde, (byte) 0xfd, (byte) 0x8e, (byte) 0x2f,
            (byte) 0x03, (byte) 0xff, (byte) 0x6a, (byte) 0x72, (byte) 0x6d, (byte) 0x6c, (byte) 0x5b, (byte) 0x51,
            (byte) 0x8d, (byte) 0x1b, (byte) 0xaf, (byte) 0x92, (byte) 0xbb, (byte) 0xdd, (byte) 0xbc, (byte) 0x7f,
            (byte) 0x11, (byte) 0xd9, (byte) 0x5c, (byte) 0x41, (byte) 0x1f, (byte) 0x10, (byte) 0x5a, (byte) 0xd8,
            (byte) 0x0a, (byte) 0xc1, (byte) 0x31, (byte) 0x88, (byte) 0xa5, (byte) 0xcd, (byte) 0x7b, (byte) 0xbd,
            (byte) 0x2d, (byte) 0x74, (byte) 0xd0, (byte) 0x12, (byte) 0xb8, (byte) 0xe5, (byte) 0xb4, (byte) 0xb0,
            (byte) 0x89, (byte) 0x69, (byte) 0x97, (byte) 0x4a, (byte) 0x0c, (byte) 0x96, (byte) 0x77, (byte) 0x7e,
            (byte) 0x65, (byte) 0xb9, (byte) 0xf1, (byte) 0x09, (byte) 0xc5, (byte) 0x6e, (byte) 0xc6, (byte) 0x84,
            (byte) 0x18, (byte) 0xf0, (byte) 0x7d, (byte) 0xec, (byte) 0x3a, (byte) 0xdc, (byte) 0x4d, (byte) 0x20,
            (byte) 0x79, (byte) 0xee, (byte) 0x5f, (byte) 0x3e, (byte) 0xd7, (byte) 0xcb, (byte) 0x39, (byte) 0x48
    };

    private static final int[] SM4_FK = {0xa3b1bac6, 0x56aa3350, 0x677d9197, 0xb27022dc};

    private static final int[] SM4_CK = {
            0x00070e15, 0x1c232a31, 0x383f464d, 0x545b6269,
            0x70777e85, 0x8c939aa1, 0xa8afb6bd, 0xc4cbd2d9,
            0xe0e7eef5, 0xfc030a11, 0x181f262d, 0x343b4249,
            0x50575e65, 0x6c737a81, 0x888f969d, 0xa4abb2b9,
            0xc0c7ced5, 0xdce3eaf1, 0xf8ff060d, 0x141b2229,
            0x30373e45, 0x4c535a61, 0x686f767d, 0x848b9299,
            0xa0a7aeb5, 0xbcc3cad1, 0xd8dfe6ed, 0xf4fb0209,
            0x10171e25, 0x2c333a41, 0x484f565d, 0x646b7279
    };

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
     * 生成国密 SM4 算法专用的 128-bit（16 字节）安全随机密钥
     *
     * @return 16 字节 SM4 随机密钥
     */
    public static byte[] generateSm4Key() {
        return generateKey(128);
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

    /**
     * 对字节数组进行无填充 URL 安全 Base64URL 编码
     *
     * @param data 原始字节数组
     * @return 无填充 Base64URL 字符串；入参为 null 时返回 null
     */
    public static String encodeBase64Url(byte[] data) {
        if (data == null) {
            return null;
        }
        return BASE64_URL_ENCODER.encodeToString(data);
    }

    /**
     * 对 URL 安全 Base64URL 字符串进行解码
     *
     * @param base64UrlStr Base64URL 编码字符串
     * @return 原始字节数组；入参为 null 时返回 null
     */
    public static byte[] decodeBase64Url(String base64UrlStr) {
        if (base64UrlStr == null) {
            return null;
        }
        return BASE64_URL_DECODER.decode(base64UrlStr);
    }

    /**
     * 恒定时间安全比对两个字节数组（防侧信道时序攻击）
     *
     * @param expected 预期字节数组
     * @param actual   实际字节数组
     * @return 完全相等返回 {@code true}；任一为 {@code null} 或内容不匹配返回 {@code false}
     */
    public static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 恒定时间安全比对两个 UTF-8 字符串（防侧信道时序攻击）
     *
     * @param expected 预期字符串
     * @param actual   实际字符串
     * @return 完全相等返回 {@code true}；任一为 {@code null} 或内容不匹配返回 {@code false}
     */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    // ==================== AES/CBC/PKCS5Padding ====================

    /**
     * AES/CBC/PKCS5Padding 加密（字节数组）
     *
     * <p>注意：CBC 模式缺少完整性认证标签，高安全与网络传输场景优先选用 {@link #encryptGcm} 或 {@link #encryptGcmPacked}。</p>
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
            throw new IllegalStateException("AES/CBC 加密执行失败", e);
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
            throw new IllegalStateException("AES/CBC 解密执行失败", e);
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
     * @throws IllegalStateException 底层密码引擎执行失败时抛出（保留原始 cause）
     */
    public static byte[] encryptGcm(byte[] plaintext, byte[] key, byte[] iv, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        validateKeyAndIv(key, iv, -1);
        try {
            return doGcmCipher(Cipher.ENCRYPT_MODE, plaintext, key, iv, aad);
        } catch (Exception e) {
            throw new IllegalStateException("AES/GCM 加密执行失败", e);
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
     * @throws IllegalStateException 解密或认证 Tag 校验失败时抛出（保留原始 {@link AEADBadTagException} cause）
     */
    public static byte[] decryptGcm(byte[] ciphertext, byte[] key, byte[] iv, byte[] aad) {
        if (ciphertext == null) {
            return null;
        }
        validateKeyAndIv(key, iv, -1);
        try {
            return doGcmCipher(Cipher.DECRYPT_MODE, ciphertext, key, iv, aad);
        } catch (Exception e) {
            throw new IllegalStateException("AES/GCM 解密或认证校验失败", e);
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

    /**
     * AES/GCM/NoPadding 加密字符串（绑定可选 AAD 附加认证数据），返回 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       AES 密钥（16/24/32 字节）
     * @param iv        初始向量（推荐 12 字节）
     * @param aad       附加认证数据（可为 null）
     * @return Base64 编码密文；明文为 null 时返回 null
     * @throws BusinessException 加密失败时抛出（保留脱敏文案与原始 cause 链）
     */
    public static String encryptGcm(String plaintext, byte[] key, byte[] iv, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] encrypted = encryptGcm(plaintext.getBytes(StandardCharsets.UTF_8), key, iv, aad);
            return encodeBase64(encrypted);
        } catch (Exception e) {
            Throwable rootCause = (e instanceof IllegalStateException && e.getCause() != null) ? e.getCause() : e;
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "AES/GCM 加密执行失败", rootCause);
        }
    }

    /**
     * AES/GCM/NoPadding 解密带 AAD 认证的 Base64 密文，返回明文字符串
     * 当密文或 AAD 遭篡改时抛出保留底层 AEAD 异常原因链的 {@link BusinessException}
     *
     * @param ciphertextBase64 Base64 编码密文
     * @param key              AES 密钥（16/24/32 字节）
     * @param iv               初始向量（与加密时一致）
     * @param aad              附加认证数据（与加密时一致，可为 null）
     * @return UTF-8 明文字符串；密文为 null 时返回 null
     * @throws BusinessException 解密或认证校验失败时抛出（保留脱敏文案与原始 cause 链）
     */
    public static String decryptGcm(String ciphertextBase64, byte[] key, byte[] iv, byte[] aad) {
        if (ciphertextBase64 == null) {
            return null;
        }
        try {
            byte[] cipherBytes = decodeBase64(ciphertextBase64);
            byte[] decrypted = decryptGcm(cipherBytes, key, iv, aad);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Throwable rootCause = (e instanceof IllegalStateException && e.getCause() != null) ? e.getCause() : e;
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "AES/GCM 解密或认证校验失败", rootCause);
        }
    }

    // ==================== 自包含封装版 AES-GCM (12B IV || ciphertext || 16B Tag) ====================

    /**
     * 自包含封装版 AES/GCM/NoPadding 加密（自动生成 12 字节随机 IV 并输出 {@code iv || ciphertext || tag}）
     *
     * @param plaintext 明文字节数组
     * @param key       AES 密钥（16/24/32 字节）
     * @return 封装密文字节数组（总长至少 28 字节）；明文为 null 时返回 null
     */
    public static byte[] encryptGcmPacked(byte[] plaintext, byte[] key) {
        return encryptGcmPacked(plaintext, key, null);
    }

    /**
     * 自包含封装版 AES/GCM/NoPadding 加密（绑定可选 AAD，输出 {@code 12B IV || ciphertext || 16B Tag}）
     *
     * @param plaintext 明文字节数组
     * @param key       AES 密钥（16/24/32 字节）
     * @param aad       附加认证数据（可为 null）
     * @return 封装密文字节数组（总长至少 28 字节）；明文为 null 时返回 null
     * @throws IllegalArgumentException 密钥非法时抛出
     * @throws IllegalStateException    底层密码引擎加密失败时抛出
     */
    public static byte[] encryptGcmPacked(byte[] plaintext, byte[] key, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        validateAesKey(key);
        byte[] iv = generateGcmIv();
        byte[] cipherAndTag = encryptGcm(plaintext, key, iv, aad);
        byte[] packed = new byte[DEFAULT_GCM_IV_SIZE + cipherAndTag.length];
        System.arraycopy(iv, 0, packed, 0, DEFAULT_GCM_IV_SIZE);
        System.arraycopy(cipherAndTag, 0, packed, DEFAULT_GCM_IV_SIZE, cipherAndTag.length);
        return packed;
    }

    /**
     * 自包含封装版 AES/GCM/NoPadding 解密（输入 {@code 12B IV || ciphertext || 16B Tag}）
     *
     * @param packedCiphertext 封装密文字节数组
     * @param key              AES 密钥（16/24/32 字节）
     * @return 明文字节数组；密文为 null 时返回 null
     */
    public static byte[] decryptGcmPacked(byte[] packedCiphertext, byte[] key) {
        return decryptGcmPacked(packedCiphertext, key, null);
    }

    /**
     * 自包含封装版 AES/GCM/NoPadding 解密（输入 {@code 12B IV || ciphertext || 16B Tag}，支持 AAD）
     *
     * @param packedCiphertext 封装密文字节数组（长度必须 {@code >= 28}）
     * @param key              AES 密钥（16/24/32 字节）
     * @param aad              附加认证数据（可为 null）
     * @return 明文字节数组；密文为 null 时返回 null
     * @throws IllegalArgumentException 密钥非法或封装密文不足 28 字节时抛出
     * @throws IllegalStateException    认证校验失败时抛出（保留 {@link AEADBadTagException} cause）
     */
    public static byte[] decryptGcmPacked(byte[] packedCiphertext, byte[] key, byte[] aad) {
        if (packedCiphertext == null) {
            return null;
        }
        validateAesKey(key);
        if (packedCiphertext.length < PACKED_GCM_MIN_BYTES) {
            throw new IllegalArgumentException("AES/GCM 封装密文长度非法，至少需要 28 字节");
        }
        byte[] iv = Arrays.copyOfRange(packedCiphertext, 0, DEFAULT_GCM_IV_SIZE);
        byte[] cipherAndTag = Arrays.copyOfRange(packedCiphertext, DEFAULT_GCM_IV_SIZE, packedCiphertext.length);
        return decryptGcm(cipherAndTag, key, iv, aad);
    }

    /**
     * 自包含封装版 AES/GCM/NoPadding 加密字符串，返回标准 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       AES 密钥（16/24/32 字节）
     * @return 标准 Base64 编码封装密文；明文为 null 时返回 null
     */
    public static String encryptGcmPacked(String plaintext, byte[] key) {
        return encryptGcmPacked(plaintext, key, null);
    }

    /**
     * 自包含封装版 AES/GCM/NoPadding 加密字符串（绑定可选 AAD），返回标准 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       AES 密钥（16/24/32 字节）
     * @param aad       附加认证数据（可为 null）
     * @return 标准 Base64 编码封装密文；明文为 null 时返回 null
     */
    public static String encryptGcmPacked(String plaintext, byte[] key, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        return encodeBase64(encryptGcmPacked(plaintext.getBytes(StandardCharsets.UTF_8), key, aad));
    }

    /**
     * 自包含封装版 AES/GCM/NoPadding 解密标准 Base64 封装密文，返回 UTF-8 明文字符串
     *
     * @param packedBase64 标准 Base64 编码封装密文
     * @param key          AES 密钥（16/24/32 字节）
     * @return UTF-8 明文字符串；密文为 null 时返回 null
     */
    public static String decryptGcmPacked(String packedBase64, byte[] key) {
        return decryptGcmPacked(packedBase64, key, null);
    }

    /**
     * 自包含封装版 AES/GCM/NoPadding 解密标准 Base64 封装密文（绑定可选 AAD），返回 UTF-8 明文字符串
     *
     * @param packedBase64 标准 Base64 编码封装密文
     * @param key          AES 密钥（16/24/32 字节）
     * @param aad          附加认证数据（可为 null）
     * @return UTF-8 明文字符串；密文为 null 时返回 null
     */
    public static String decryptGcmPacked(String packedBase64, byte[] key, byte[] aad) {
        if (packedBase64 == null) {
            return null;
        }
        byte[] decrypted = decryptGcmPacked(decodeBase64(packedBase64), key, aad);
        try {
            return decrypted != null ? new String(decrypted, StandardCharsets.UTF_8) : null;
        } finally {
            if (decrypted != null) {
                Arrays.fill(decrypted, (byte) 0);
            }
        }
    }

    private static byte[] doGcmCipher(int mode, byte[] input, byte[] key, byte[] iv, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION_AES_GCM);
        SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM_AES);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(mode, keySpec, gcmSpec);
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(input);
    }

    // ==================== 国密 SM4 纯原生实现 (GB/T 32907-2016) ====================

    /**
     * SM4/CBC/PKCS7Padding 加密（字节数组）
     *
     * <p>注意：CBC 模式缺少完整性认证标签，高安全与网络传输场景优先选用 {@link #encryptSm4Gcm} 或 {@link #encryptSm4GcmPacked}。</p>
     *
     * @param plaintext 明文字节数组
     * @param key       SM4 密钥（必须为 16 字节）
     * @param iv        初始向量（必须为 16 字节）
     * @return 密文字节数组；明文为 null 时返回 null
     */
    public static byte[] encryptSm4Cbc(byte[] plaintext, byte[] key, byte[] iv) {
        if (plaintext == null) {
            return null;
        }
        validateSm4KeyAndIv(key, iv, true);
        byte[] padded = pkcs7Pad(plaintext);
        int[] rk = sm4KeyExpansion(key);
        byte[] ciphertext = new byte[padded.length];
        byte[] currentIv = new byte[SM4_IV_SIZE];
        byte[] block = new byte[SM4_BLOCK_SIZE];
        try {
            System.arraycopy(iv, 0, currentIv, 0, SM4_IV_SIZE);
            for (int offset = 0; offset < padded.length; offset += SM4_BLOCK_SIZE) {
                for (int i = 0; i < SM4_BLOCK_SIZE; i++) {
                    block[i] = (byte) (padded[offset + i] ^ currentIv[i]);
                }
                sm4CryptBlock(block, 0, ciphertext, offset, rk);
                System.arraycopy(ciphertext, offset, currentIv, 0, SM4_BLOCK_SIZE);
            }
            return ciphertext;
        } finally {
            Arrays.fill(rk, 0);
            Arrays.fill(currentIv, (byte) 0);
            Arrays.fill(block, (byte) 0);
            Arrays.fill(padded, (byte) 0);
        }
    }

    /**
     * SM4/CBC/PKCS7Padding 解密（字节数组）
     *
     * @param ciphertext 密文字节数组
     * @param key        SM4 密钥（必须为 16 字节）
     * @param iv         初始向量（必须为 16 字节）
     * @return 明文字节数组；密文为 null 时返回 null
     */
    public static byte[] decryptSm4Cbc(byte[] ciphertext, byte[] key, byte[] iv) {
        if (ciphertext == null) {
            return null;
        }
        validateSm4KeyAndIv(key, iv, true);
        if (ciphertext.length == 0 || ciphertext.length % SM4_BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("SM4 密文长度非法，必须为 16 字节的非零整数倍");
        }
        int[] rk = sm4KeyExpansion(key);
        int[] invRk = new int[32];
        byte[] plaintextPadded = new byte[ciphertext.length];
        byte[] currentIv = new byte[SM4_IV_SIZE];
        byte[] block = new byte[SM4_BLOCK_SIZE];
        try {
            for (int i = 0; i < 32; i++) {
                invRk[i] = rk[31 - i];
            }
            System.arraycopy(iv, 0, currentIv, 0, SM4_IV_SIZE);

            for (int offset = 0; offset < ciphertext.length; offset += SM4_BLOCK_SIZE) {
                sm4CryptBlock(ciphertext, offset, block, 0, invRk);
                for (int i = 0; i < SM4_BLOCK_SIZE; i++) {
                    plaintextPadded[offset + i] = (byte) (block[i] ^ currentIv[i]);
                }
                System.arraycopy(ciphertext, offset, currentIv, 0, SM4_BLOCK_SIZE);
            }
            return pkcs7Unpad(plaintextPadded);
        } finally {
            Arrays.fill(rk, 0);
            Arrays.fill(invRk, 0);
            Arrays.fill(currentIv, (byte) 0);
            Arrays.fill(block, (byte) 0);
            Arrays.fill(plaintextPadded, (byte) 0);
        }
    }

    /**
     * SM4/CBC/PKCS7Padding 加密字符串，返回 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       SM4 密钥（必须为 16 字节）
     * @param iv        初始向量（必须为 16 字节）
     * @return Base64 格式密文字符串；明文为 null 时返回 null
     */
    public static String encryptSm4Cbc(String plaintext, byte[] key, byte[] iv) {
        if (plaintext == null) {
            return null;
        }
        byte[] encrypted = encryptSm4Cbc(plaintext.getBytes(StandardCharsets.UTF_8), key, iv);
        return encodeBase64(encrypted);
    }

    /**
     * SM4/CBC/PKCS7Padding 解密 Base64 密文，返回明文字符串
     *
     * @param base64Ciphertext Base64 编码密文
     * @param key              SM4 密钥（必须为 16 字节）
     * @param iv               初始向量（必须为 16 字节）
     * @return UTF-8 明文字符串；密文为 null 时返回 null
     */
    public static String decryptSm4Cbc(String base64Ciphertext, byte[] key, byte[] iv) {
        if (base64Ciphertext == null) {
            return null;
        }
        byte[] decrypted = decryptSm4Cbc(decodeBase64(base64Ciphertext), key, iv);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * SM4/ECB/PKCS7Padding 加密（字节数组）
     *
     * <p>注意：ECB 模式不隐蔽明文数据模式且无完整性认证，仅限硬件对齐兼容场景，优先选用 {@link #encryptSm4Gcm} 或 {@link #encryptSm4GcmPacked}。</p>
     *
     * @param plaintext 明文字节数组
     * @param key       SM4 密钥（必须为 16 字节）
     * @return 密文字节数组；明文为 null 时返回 null
     */
    public static byte[] encryptSm4Ecb(byte[] plaintext, byte[] key) {
        if (plaintext == null) {
            return null;
        }
        validateSm4KeyAndIv(key, null, false);
        byte[] padded = pkcs7Pad(plaintext);
        int[] rk = sm4KeyExpansion(key);
        byte[] ciphertext = new byte[padded.length];
        try {
            for (int offset = 0; offset < padded.length; offset += SM4_BLOCK_SIZE) {
                sm4CryptBlock(padded, offset, ciphertext, offset, rk);
            }
            return ciphertext;
        } finally {
            Arrays.fill(rk, 0);
            Arrays.fill(padded, (byte) 0);
        }
    }

    /**
     * SM4/ECB/PKCS7Padding 解密（字节数组）
     *
     * @param ciphertext 密文字节数组
     * @param key        SM4 密钥（必须为 16 字节）
     * @return 明文字节数组；密文为 null 时返回 null
     */
    public static byte[] decryptSm4Ecb(byte[] ciphertext, byte[] key) {
        if (ciphertext == null) {
            return null;
        }
        validateSm4KeyAndIv(key, null, false);
        if (ciphertext.length == 0 || ciphertext.length % SM4_BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("SM4 密文长度非法，必须为 16 字节的非零整数倍");
        }
        int[] rk = sm4KeyExpansion(key);
        int[] invRk = new int[32];
        byte[] plaintextPadded = new byte[ciphertext.length];
        try {
            for (int i = 0; i < 32; i++) {
                invRk[i] = rk[31 - i];
            }
            for (int offset = 0; offset < ciphertext.length; offset += SM4_BLOCK_SIZE) {
                sm4CryptBlock(ciphertext, offset, plaintextPadded, offset, invRk);
            }
            return pkcs7Unpad(plaintextPadded);
        } finally {
            Arrays.fill(rk, 0);
            Arrays.fill(invRk, 0);
            Arrays.fill(plaintextPadded, (byte) 0);
        }
    }

    /**
     * SM4/ECB/PKCS7Padding 加密字符串，返回 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       SM4 密钥（必须为 16 字节）
     * @return Base64 编码密文
     */
    public static String encryptSm4Ecb(String plaintext, byte[] key) {
        if (plaintext == null) {
            return null;
        }
        byte[] encrypted = encryptSm4Ecb(plaintext.getBytes(StandardCharsets.UTF_8), key);
        return encodeBase64(encrypted);
    }

    /**
     * SM4/ECB/PKCS7Padding 解密 Base64 密文，返回明文字符串
     *
     * @param base64Ciphertext Base64 编码密文
     * @param key              SM4 密钥（必须为 16 字节）
     * @return UTF-8 明文字符串
     */
    public static String decryptSm4Ecb(String base64Ciphertext, byte[] key) {
        if (base64Ciphertext == null) {
            return null;
        }
        byte[] decrypted = decryptSm4Ecb(decodeBase64(base64Ciphertext), key);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    // ==================== 国密 SM4/GCM/NoPadding 纯原生实现 (NIST SP 800-38D) ====================

    /**
     * 国密 SM4/GCM/NoPadding 认证加密（字节数组，支持可选 AAD 附加认证数据）
     *
     * @param plaintext 明文字节数组
     * @param key       SM4 密钥（必须为 16 字节）
     * @param iv        初始向量（推荐 12 字节）
     * @param aad       附加认证数据（可为 null）
     * @return 末尾附带 16 字节（128-bit）GHASH 认证标签的密文字节数组；明文为 null 时返回 null
     */
    public static byte[] encryptSm4Gcm(byte[] plaintext, byte[] key, byte[] iv, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        validateSm4GcmKeyAndIv(key, iv);
        int[] rk = sm4KeyExpansion(key);
        byte[] h = new byte[SM4_BLOCK_SIZE];
        byte[] j0 = null;
        byte[] counter = null;
        byte[] tagMask = new byte[SM4_BLOCK_SIZE];
        byte[] s = null;
        try {
            sm4CryptBlock(new byte[SM4_BLOCK_SIZE], 0, h, 0, rk);
            j0 = computeSm4GcmJ0(h, iv);
            counter = j0.clone();
            inc32(counter);

            byte[] out = new byte[plaintext.length + SM4_GCM_TAG_BYTES];
            sm4Gctr(rk, counter, plaintext, 0, plaintext.length, out, 0);

            s = sm4Ghash(h, aad, out, plaintext.length);
            sm4CryptBlock(j0, 0, tagMask, 0, rk);
            for (int i = 0; i < SM4_GCM_TAG_BYTES; i++) {
                out[plaintext.length + i] = (byte) (s[i] ^ tagMask[i]);
            }
            return out;
        } finally {
            Arrays.fill(rk, 0);
            Arrays.fill(h, (byte) 0);
            if (j0 != null) Arrays.fill(j0, (byte) 0);
            if (counter != null) Arrays.fill(counter, (byte) 0);
            Arrays.fill(tagMask, (byte) 0);
            if (s != null) Arrays.fill(s, (byte) 0);
        }
    }

    /**
     * 国密 SM4/GCM/NoPadding 认证加密（字节数组）
     *
     * @param plaintext 明文字节数组
     * @param key       SM4 密钥（必须为 16 字节）
     * @param iv        初始向量（推荐 12 字节）
     * @return 末尾附带 16 字节认证标签的密文字节数组
     */
    public static byte[] encryptSm4Gcm(byte[] plaintext, byte[] key, byte[] iv) {
        return encryptSm4Gcm(plaintext, key, iv, null);
    }

    /**
     * 国密 SM4/GCM/NoPadding 认证解密（字节数组，支持可选 AAD 附加认证数据校验）
     *
     * @param ciphertext 末尾附带 16 字节认证标签的密文字节数组
     * @param key        SM4 密钥（必须为 16 字节）
     * @param iv         初始向量（与加密时一致）
     * @param aad        附加认证数据（与加密时一致，可为 null）
     * @return 明文字节数组；密文为 null 时返回 null
     * @throws IllegalStateException 解密或认证标签校验失败时抛出（保留 {@link AEADBadTagException} cause）
     */
    public static byte[] decryptSm4Gcm(byte[] ciphertext, byte[] key, byte[] iv, byte[] aad) {
        if (ciphertext == null) {
            return null;
        }
        validateSm4GcmKeyAndIv(key, iv);
        if (ciphertext.length < SM4_GCM_TAG_BYTES) {
            throw new IllegalStateException("SM4/GCM 解密或认证校验失败",
                    new AEADBadTagException("SM4/GCM ciphertext shorter than 16-byte tag"));
        }
        int cipherLen = ciphertext.length - SM4_GCM_TAG_BYTES;
        int[] rk = sm4KeyExpansion(key);
        byte[] h = new byte[SM4_BLOCK_SIZE];
        byte[] j0 = null;
        byte[] s = null;
        byte[] tagMask = new byte[SM4_BLOCK_SIZE];
        byte[] expectedTag = new byte[SM4_GCM_TAG_BYTES];
        byte[] providedTag = new byte[SM4_GCM_TAG_BYTES];
        byte[] counter = null;
        try {
            sm4CryptBlock(new byte[SM4_BLOCK_SIZE], 0, h, 0, rk);
            j0 = computeSm4GcmJ0(h, iv);
            s = sm4Ghash(h, aad, ciphertext, cipherLen);
            sm4CryptBlock(j0, 0, tagMask, 0, rk);
            for (int i = 0; i < SM4_GCM_TAG_BYTES; i++) {
                expectedTag[i] = (byte) (s[i] ^ tagMask[i]);
            }
            System.arraycopy(ciphertext, cipherLen, providedTag, 0, SM4_GCM_TAG_BYTES);
            if (!MessageDigest.isEqual(expectedTag, providedTag)) {
                throw new IllegalStateException("SM4/GCM 解密或认证校验失败",
                        new AEADBadTagException("Tag mismatch!"));
            }

            counter = j0.clone();
            inc32(counter);
            byte[] plaintext = new byte[cipherLen];
            sm4Gctr(rk, counter, ciphertext, 0, cipherLen, plaintext, 0);
            return plaintext;
        } finally {
            Arrays.fill(rk, 0);
            Arrays.fill(h, (byte) 0);
            if (j0 != null) Arrays.fill(j0, (byte) 0);
            if (s != null) Arrays.fill(s, (byte) 0);
            Arrays.fill(tagMask, (byte) 0);
            Arrays.fill(expectedTag, (byte) 0);
            Arrays.fill(providedTag, (byte) 0);
            if (counter != null) Arrays.fill(counter, (byte) 0);
        }
    }

    /**
     * 国密 SM4/GCM/NoPadding 认证解密（字节数组）
     *
     * @param ciphertext 密文字节数组（末尾附带 16 字节认证标签）
     * @param key        SM4 密钥（必须为 16 字节）
     * @param iv         初始向量
     * @return 明文字节数组
     */
    public static byte[] decryptSm4Gcm(byte[] ciphertext, byte[] key, byte[] iv) {
        return decryptSm4Gcm(ciphertext, key, iv, null);
    }

    /**
     * 国密 SM4/GCM/NoPadding 加密字符串，返回 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       SM4 密钥（必须为 16 字节）
     * @param iv        初始向量（推荐 12 字节）
     * @return Base64 编码密文；明文为 null 时返回 null
     */
    public static String encryptSm4Gcm(String plaintext, byte[] key, byte[] iv) {
        if (plaintext == null) {
            return null;
        }
        byte[] encrypted = encryptSm4Gcm(plaintext.getBytes(StandardCharsets.UTF_8), key, iv);
        return encodeBase64(encrypted);
    }

    /**
     * 国密 SM4/GCM/NoPadding 解密 Base64 密文，返回明文字符串
     *
     * @param base64Ciphertext Base64 编码密文
     * @param key              SM4 密钥（必须为 16 字节）
     * @param iv               初始向量
     * @return UTF-8 明文字符串；密文为 null 时返回 null
     */
    public static String decryptSm4Gcm(String base64Ciphertext, byte[] key, byte[] iv) {
        if (base64Ciphertext == null) {
            return null;
        }
        byte[] decrypted = decryptSm4Gcm(decodeBase64(base64Ciphertext), key, iv);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * 国密 SM4/GCM/NoPadding 加密字符串（绑定可选 AAD 附加认证数据），返回 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       SM4 密钥（必须为 16 字节）
     * @param iv        初始向量（推荐 12 字节）
     * @param aad       附加认证数据（可为 null）
     * @return Base64 编码密文；明文为 null 时返回 null
     * @throws BusinessException 加密失败时抛出（保留脱敏文案与原始 cause 链）
     */
    public static String encryptSm4Gcm(String plaintext, byte[] key, byte[] iv, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] encrypted = encryptSm4Gcm(plaintext.getBytes(StandardCharsets.UTF_8), key, iv, aad);
            return encodeBase64(encrypted);
        } catch (Exception e) {
            Throwable rootCause = (e instanceof IllegalStateException && e.getCause() != null) ? e.getCause() : e;
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "SM4/GCM 加密执行失败", rootCause);
        }
    }

    /**
     * 国密 SM4/GCM/NoPadding 解密带 AAD 认证的 Base64 密文，返回明文字符串
     * 当密文或 AAD 遭篡改时抛出保留底层 AEAD 异常原因链的 {@link BusinessException}
     *
     * @param ciphertextBase64 Base64 编码密文
     * @param key              SM4 密钥（必须为 16 字节）
     * @param iv               初始向量（与加密时一致）
     * @param aad              附加认证数据（与加密时一致，可为 null）
     * @return UTF-8 明文字符串；密文为 null 时返回 null
     * @throws BusinessException 解密或认证校验失败时抛出（保留脱敏文案与原始 cause 链）
     */
    public static String decryptSm4Gcm(String ciphertextBase64, byte[] key, byte[] iv, byte[] aad) {
        if (ciphertextBase64 == null) {
            return null;
        }
        try {
            byte[] cipherBytes = decodeBase64(ciphertextBase64);
            byte[] decrypted = decryptSm4Gcm(cipherBytes, key, iv, aad);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Throwable rootCause = (e instanceof IllegalStateException && e.getCause() != null) ? e.getCause() : e;
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "SM4/GCM 解密或认证校验失败", rootCause);
        }
    }

    // ==================== 自包含封装版 SM4-GCM (12B IV || ciphertext || 16B Tag) ====================

    /**
     * 自包含封装版国密 SM4/GCM/NoPadding 加密（自动生成 12 字节随机 IV，输出 {@code iv || ciphertext || tag}）
     *
     * @param plaintext 明文字节数组
     * @param key       SM4 密钥（必须为 16 字节）
     * @return 封装密文字节数组（总长至少 28 字节）；明文为 null 时返回 null
     */
    public static byte[] encryptSm4GcmPacked(byte[] plaintext, byte[] key) {
        return encryptSm4GcmPacked(plaintext, key, null);
    }

    /**
     * 自包含封装版国密 SM4/GCM/NoPadding 加密（绑定可选 AAD，输出 {@code 12B IV || ciphertext || 16B Tag}）
     *
     * @param plaintext 明文字节数组
     * @param key       SM4 密钥（必须为 16 字节）
     * @param aad       附加认证数据（可为 null）
     * @return 封装密文字节数组（总长至少 28 字节）；明文为 null 时返回 null
     * @throws IllegalArgumentException 密钥非法时抛出
     */
    public static byte[] encryptSm4GcmPacked(byte[] plaintext, byte[] key, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        validateSm4KeyAndIv(key, null, false);
        byte[] iv = generateGcmIv();
        byte[] cipherAndTag = encryptSm4Gcm(plaintext, key, iv, aad);
        byte[] packed = new byte[DEFAULT_GCM_IV_SIZE + cipherAndTag.length];
        System.arraycopy(iv, 0, packed, 0, DEFAULT_GCM_IV_SIZE);
        System.arraycopy(cipherAndTag, 0, packed, DEFAULT_GCM_IV_SIZE, cipherAndTag.length);
        return packed;
    }

    /**
     * 自包含封装版国密 SM4/GCM/NoPadding 解密（输入 {@code 12B IV || ciphertext || 16B Tag}）
     *
     * @param packedCiphertext 封装密文字节数组
     * @param key              SM4 密钥（必须为 16 字节）
     * @return 明文字节数组；密文为 null 时返回 null
     */
    public static byte[] decryptSm4GcmPacked(byte[] packedCiphertext, byte[] key) {
        return decryptSm4GcmPacked(packedCiphertext, key, null);
    }

    /**
     * 自包含封装版国密 SM4/GCM/NoPadding 解密（输入 {@code 12B IV || ciphertext || 16B Tag}，支持 AAD）
     *
     * @param packedCiphertext 封装密文字节数组（长度必须 {@code >= 28}）
     * @param key              SM4 密钥（必须为 16 字节）
     * @param aad              附加认证数据（可为 null）
     * @return 明文字节数组；密文为 null 时返回 null
     * @throws IllegalArgumentException 密钥非法或封装密文不足 28 字节时抛出
     * @throws IllegalStateException    认证校验失败时抛出（保留 {@link AEADBadTagException} cause）
     */
    public static byte[] decryptSm4GcmPacked(byte[] packedCiphertext, byte[] key, byte[] aad) {
        if (packedCiphertext == null) {
            return null;
        }
        validateSm4KeyAndIv(key, null, false);
        if (packedCiphertext.length < PACKED_GCM_MIN_BYTES) {
            throw new IllegalArgumentException("SM4/GCM 封装密文长度非法，至少需要 28 字节");
        }
        byte[] iv = Arrays.copyOfRange(packedCiphertext, 0, DEFAULT_GCM_IV_SIZE);
        byte[] cipherAndTag = Arrays.copyOfRange(packedCiphertext, DEFAULT_GCM_IV_SIZE, packedCiphertext.length);
        return decryptSm4Gcm(cipherAndTag, key, iv, aad);
    }

    /**
     * 自包含封装版国密 SM4/GCM/NoPadding 加密字符串，返回标准 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       SM4 密钥（必须为 16 字节）
     * @return 标准 Base64 编码封装密文；明文为 null 时返回 null
     */
    public static String encryptSm4GcmPacked(String plaintext, byte[] key) {
        return encryptSm4GcmPacked(plaintext, key, null);
    }

    /**
     * 自包含封装版国密 SM4/GCM/NoPadding 加密字符串（绑定可选 AAD），返回标准 Base64 编码密文
     *
     * @param plaintext 明文字符串（UTF-8）
     * @param key       SM4 密钥（必须为 16 字节）
     * @param aad       附加认证数据（可为 null）
     * @return 标准 Base64 编码封装密文；明文为 null 时返回 null
     */
    public static String encryptSm4GcmPacked(String plaintext, byte[] key, byte[] aad) {
        if (plaintext == null) {
            return null;
        }
        return encodeBase64(encryptSm4GcmPacked(plaintext.getBytes(StandardCharsets.UTF_8), key, aad));
    }

    /**
     * 自包含封装版国密 SM4/GCM/NoPadding 解密标准 Base64 封装密文，返回 UTF-8 明文字符串
     *
     * @param packedBase64 标准 Base64 编码封装密文
     * @param key          SM4 密钥（必须为 16 字节）
     * @return UTF-8 明文字符串；密文为 null 时返回 null
     */
    public static String decryptSm4GcmPacked(String packedBase64, byte[] key) {
        return decryptSm4GcmPacked(packedBase64, key, null);
    }

    /**
     * 自包含封装版国密 SM4/GCM/NoPadding 解密标准 Base64 封装密文（绑定可选 AAD），返回 UTF-8 明文字符串
     *
     * @param packedBase64 标准 Base64 编码封装密文
     * @param key          SM4 密钥（必须为 16 字节）
     * @param aad          附加认证数据（可为 null）
     * @return UTF-8 明文字符串；密文为 null 时返回 null
     */
    public static String decryptSm4GcmPacked(String packedBase64, byte[] key, byte[] aad) {
        if (packedBase64 == null) {
            return null;
        }
        byte[] decrypted = decryptSm4GcmPacked(decodeBase64(packedBase64), key, aad);
        try {
            return decrypted != null ? new String(decrypted, StandardCharsets.UTF_8) : null;
        } finally {
            if (decrypted != null) {
                Arrays.fill(decrypted, (byte) 0);
            }
        }
    }

    private static void validateSm4GcmKeyAndIv(byte[] key, byte[] iv) {
        validateSm4KeyAndIv(key, null, false);
        if (iv == null || iv.length == 0) {
            throw new IllegalArgumentException("IV 初始向量不能为空");
        }
    }

    private static byte[] computeSm4GcmJ0(byte[] h, byte[] iv) {
        if (iv.length == DEFAULT_GCM_IV_SIZE) {
            byte[] j0 = new byte[SM4_BLOCK_SIZE];
            System.arraycopy(iv, 0, j0, 0, DEFAULT_GCM_IV_SIZE);
            j0[15] = 0x01;
            return j0;
        }
        byte[] y = new byte[SM4_BLOCK_SIZE];
        ghashDataBlocks(y, h, iv, iv.length);
        byte[] lenBlock = new byte[SM4_BLOCK_SIZE];
        long ivBitLen = (long) iv.length * 8L;
        for (int i = 0; i < 8; i++) {
            lenBlock[8 + i] = (byte) ((ivBitLen >>> (56 - i * 8)) & 0xff);
        }
        xorBlock(y, lenBlock);
        gf128MultiplyInPlace(y, h);
        return y;
    }

    private static void sm4Gctr(int[] rk, byte[] counter, byte[] in, int inOffset, int len, byte[] out, int outOffset) {
        byte[] keystream = new byte[SM4_BLOCK_SIZE];
        try {
            int offset = 0;
            while (offset < len) {
                sm4CryptBlock(counter, 0, keystream, 0, rk);
                int blockLen = Math.min(SM4_BLOCK_SIZE, len - offset);
                for (int i = 0; i < blockLen; i++) {
                    out[outOffset + offset + i] = (byte) (in[inOffset + offset + i] ^ keystream[i]);
                }
                offset += blockLen;
                inc32(counter);
            }
        } finally {
            Arrays.fill(keystream, (byte) 0);
        }
    }

    private static byte[] sm4Ghash(byte[] h, byte[] aad, byte[] cipher, int cipherLen) {
        byte[] y = new byte[SM4_BLOCK_SIZE];
        int aadLen = aad != null ? aad.length : 0;
        if (aadLen > 0) {
            ghashDataBlocks(y, h, aad, aadLen);
        }
        if (cipherLen > 0) {
            ghashDataBlocks(y, h, cipher, cipherLen);
        }
        byte[] lenBlock = new byte[SM4_BLOCK_SIZE];
        long aadBits = (long) aadLen * 8L;
        long cipherBits = (long) cipherLen * 8L;
        for (int i = 0; i < 8; i++) {
            lenBlock[i] = (byte) ((aadBits >>> (56 - i * 8)) & 0xff);
            lenBlock[8 + i] = (byte) ((cipherBits >>> (56 - i * 8)) & 0xff);
        }
        xorBlock(y, lenBlock);
        gf128MultiplyInPlace(y, h);
        return y;
    }

    private static void ghashDataBlocks(byte[] y, byte[] h, byte[] data, int dataLen) {
        byte[] block = new byte[SM4_BLOCK_SIZE];
        int offset = 0;
        while (offset < dataLen) {
            int chunk = Math.min(SM4_BLOCK_SIZE, dataLen - offset);
            Arrays.fill(block, (byte) 0);
            System.arraycopy(data, offset, block, 0, chunk);
            xorBlock(y, block);
            gf128MultiplyInPlace(y, h);
            offset += chunk;
        }
    }

    private static void xorBlock(byte[] target, byte[] other) {
        for (int i = 0; i < SM4_BLOCK_SIZE; i++) {
            target[i] ^= other[i];
        }
    }

    private static void inc32(byte[] counter) {
        for (int i = 15; i >= 12; i--) {
            int next = (counter[i] & 0xff) + 1;
            counter[i] = (byte) next;
            if ((next & 0x100) == 0) {
                break;
            }
        }
    }

    /**
     * 基于 64 位无分支位掩码实现的恒定时间 GF(2^128) 乘法 (NIST SP 800-38D Algorithm 1)，结果原地写回 x
     */
    private static void gf128MultiplyInPlace(byte[] x, byte[] h) {
        long xHi = bytesToLong(x, 0);
        long xLo = bytesToLong(x, 8);
        long vHi = bytesToLong(h, 0);
        long vLo = bytesToLong(h, 8);
        long zHi = 0L;
        long zLo = 0L;

        for (int i = 0; i < 64; i++) {
            long bitMask = -((xHi >>> (63 - i)) & 1L);
            zHi ^= (vHi & bitMask);
            zLo ^= (vLo & bitMask);
            long lsbMask = -(vLo & 1L);
            vLo = (vLo >>> 1) | ((vHi & 1L) << 63);
            vHi = (vHi >>> 1) ^ (0xE100000000000000L & lsbMask);
        }
        for (int i = 0; i < 64; i++) {
            long bitMask = -((xLo >>> (63 - i)) & 1L);
            zHi ^= (vHi & bitMask);
            zLo ^= (vLo & bitMask);
            long lsbMask = -(vLo & 1L);
            vLo = (vLo >>> 1) | ((vHi & 1L) << 63);
            vHi = (vHi >>> 1) ^ (0xE100000000000000L & lsbMask);
        }

        longToBytes(zHi, x, 0);
        longToBytes(zLo, x, 8);
    }

    private static long bytesToLong(byte[] b, int offset) {
        return ((b[offset] & 0xffL) << 56)
                | ((b[offset + 1] & 0xffL) << 48)
                | ((b[offset + 2] & 0xffL) << 40)
                | ((b[offset + 3] & 0xffL) << 32)
                | ((b[offset + 4] & 0xffL) << 24)
                | ((b[offset + 5] & 0xffL) << 16)
                | ((b[offset + 6] & 0xffL) << 8)
                | (b[offset + 7] & 0xffL);
    }

    private static void longToBytes(long val, byte[] b, int offset) {
        b[offset] = (byte) ((val >>> 56) & 0xff);
        b[offset + 1] = (byte) ((val >>> 48) & 0xff);
        b[offset + 2] = (byte) ((val >>> 40) & 0xff);
        b[offset + 3] = (byte) ((val >>> 32) & 0xff);
        b[offset + 4] = (byte) ((val >>> 24) & 0xff);
        b[offset + 5] = (byte) ((val >>> 16) & 0xff);
        b[offset + 6] = (byte) ((val >>> 8) & 0xff);
        b[offset + 7] = (byte) (val & 0xff);
    }

    // ==================== SM4 内部纯原生算法实现细节 ====================

    private static void validateSm4KeyAndIv(byte[] key, byte[] iv, boolean requireIv) {
        if (key == null || key.length != SM4_KEY_SIZE) {
            int len = key != null ? key.length : 0;
            throw new IllegalArgumentException("SM4 密钥字节长度必须为 16 字节（128 位），实际为: " + len);
        }
        if (requireIv && (iv == null || iv.length != SM4_IV_SIZE)) {
            int len = iv != null ? iv.length : 0;
            throw new IllegalArgumentException("SM4 CBC 模式要求的 IV 字节长度必须为 16 字节，实际为: " + len);
        }
    }

    private static int sm4Tau(int a) {
        return ((SM4_SBOX[(a >>> 24) & 0xff] & 0xff) << 24)
                | ((SM4_SBOX[(a >>> 16) & 0xff] & 0xff) << 16)
                | ((SM4_SBOX[(a >>> 8) & 0xff] & 0xff) << 8)
                | (SM4_SBOX[a & 0xff] & 0xff);
    }

    private static int sm4L(int b) {
        return b ^ Integer.rotateLeft(b, 2) ^ Integer.rotateLeft(b, 10)
                ^ Integer.rotateLeft(b, 18) ^ Integer.rotateLeft(b, 24);
    }

    private static int sm4LPrime(int b) {
        return b ^ Integer.rotateLeft(b, 13) ^ Integer.rotateLeft(b, 23);
    }

    private static int sm4T(int a) {
        return sm4L(sm4Tau(a));
    }

    private static int sm4TPrime(int a) {
        return sm4LPrime(sm4Tau(a));
    }

    private static int[] sm4KeyExpansion(byte[] key) {
        int[] k = new int[36];
        try {
            k[0] = bytesToInt(key, 0) ^ SM4_FK[0];
            k[1] = bytesToInt(key, 4) ^ SM4_FK[1];
            k[2] = bytesToInt(key, 8) ^ SM4_FK[2];
            k[3] = bytesToInt(key, 12) ^ SM4_FK[3];
            int[] rk = new int[32];
            for (int i = 0; i < 32; i++) {
                k[i + 4] = k[i] ^ sm4TPrime(k[i + 1] ^ k[i + 2] ^ k[i + 3] ^ SM4_CK[i]);
                rk[i] = k[i + 4];
            }
            return rk;
        } finally {
            Arrays.fill(k, 0);
        }
    }

    private static void sm4CryptBlock(byte[] in, int inOffset, byte[] out, int outOffset, int[] rk) {
        int x0 = bytesToInt(in, inOffset);
        int x1 = bytesToInt(in, inOffset + 4);
        int x2 = bytesToInt(in, inOffset + 8);
        int x3 = bytesToInt(in, inOffset + 12);
        for (int i = 0; i < 32; i += 4) {
            x0 ^= sm4T(x1 ^ x2 ^ x3 ^ rk[i]);
            x1 ^= sm4T(x2 ^ x3 ^ x0 ^ rk[i + 1]);
            x2 ^= sm4T(x3 ^ x0 ^ x1 ^ rk[i + 2]);
            x3 ^= sm4T(x0 ^ x1 ^ x2 ^ rk[i + 3]);
        }
        intToBytes(x3, out, outOffset);
        intToBytes(x2, out, outOffset + 4);
        intToBytes(x1, out, outOffset + 8);
        intToBytes(x0, out, outOffset + 12);
    }

    private static int bytesToInt(byte[] b, int offset) {
        return ((b[offset] & 0xff) << 24)
                | ((b[offset + 1] & 0xff) << 16)
                | ((b[offset + 2] & 0xff) << 8)
                | (b[offset + 3] & 0xff);
    }

    private static void intToBytes(int val, byte[] b, int offset) {
        b[offset] = (byte) ((val >>> 24) & 0xff);
        b[offset + 1] = (byte) ((val >>> 16) & 0xff);
        b[offset + 2] = (byte) ((val >>> 8) & 0xff);
        b[offset + 3] = (byte) (val & 0xff);
    }

    private static byte[] pkcs7Pad(byte[] data) {
        int padLen = SM4_BLOCK_SIZE - (data.length % SM4_BLOCK_SIZE);
        byte[] padded = new byte[data.length + padLen];
        System.arraycopy(data, 0, padded, 0, data.length);
        byte padByte = (byte) padLen;
        for (int i = data.length; i < padded.length; i++) {
            padded[i] = padByte;
        }
        return padded;
    }

    private static byte[] pkcs7Unpad(byte[] data) {
        if (data == null || data.length == 0 || data.length % SM4_BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("密文数据长度非法，无法解填充");
        }
        int padLen = data[data.length - 1] & 0xff;
        int bad = (padLen < 1 || padLen > SM4_BLOCK_SIZE) ? 1 : 0;
        int effectivePadLen = (bad == 0) ? padLen : 1;
        int start = data.length - effectivePadLen;
        for (int i = data.length - SM4_BLOCK_SIZE; i < data.length; i++) {
            int inPad = (i >= start) ? 1 : 0;
            bad |= inPad & (((data[i] & 0xff) ^ effectivePadLen) != 0 ? 1 : 0);
        }
        if (bad != 0) {
            throw new IllegalArgumentException("PKCS7 解填充失败");
        }
        byte[] unpadded = new byte[data.length - effectivePadLen];
        System.arraycopy(data, 0, unpadded, 0, unpadded.length);
        return unpadded;
    }

    private static void validateAesKey(byte[] key) {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            int len = key != null ? key.length : 0;
            throw new IllegalArgumentException("AES 密钥字节长度必须为 16, 24 或 32（128/192/256 位），实际为: " + len);
        }
    }

    private static void validateKeyAndIv(byte[] key, byte[] iv, int requiredIvLength) {
        validateAesKey(key);
        if (iv == null || iv.length == 0) {
            throw new IllegalArgumentException("IV 初始向量不能为空");
        }
        if (requiredIvLength > 0 && iv.length != requiredIvLength) {
            throw new IllegalArgumentException("当前模式要求的 IV 字节长度必须为 " + requiredIvLength + "，实际为: " + iv.length);
        }
    }
}
