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
 * 纯 JDK 原生对称加密与国密工具类
 * 基于 Zero-Bloat 原则，100% 依托 JDK 原生能力实现 AES/CBC、AES/GCM 以及纯原生国密 SM4/CBC、SM4/ECB 安全加解密，无 Bouncy Castle 等外部依赖
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

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
            0x70777e85, 0x8c939a01, 0x080f161d, 0x242b3239,
            0x40474e55, 0x5c636a71, 0x787f868d, 0x949b0209,
            0x10171e25, 0x2c333a41, 0x484f565d, 0x646b7279,
            0x80878e95, 0x9c030a11, 0x181f262d, 0x343b4249,
            0x50575e65, 0x6c737a81, 0x888f969d, 0x040b1219,
            0x20272e35, 0x3c434a51, 0x585f666d, 0x747b8289,
            0x90979e05, 0x0c131a21, 0x282f363d, 0x444b5259
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

    // ==================== 国密 SM4 纯原生实现 (GB/T 32907-2016) ====================

    /**
     * SM4/CBC/PKCS7Padding 加密（字节数组）
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
        System.arraycopy(iv, 0, currentIv, 0, SM4_IV_SIZE);

        byte[] block = new byte[SM4_BLOCK_SIZE];
        for (int offset = 0; offset < padded.length; offset += SM4_BLOCK_SIZE) {
            for (int i = 0; i < SM4_BLOCK_SIZE; i++) {
                block[i] = (byte) (padded[offset + i] ^ currentIv[i]);
            }
            sm4CryptBlock(block, 0, ciphertext, offset, rk);
            System.arraycopy(ciphertext, offset, currentIv, 0, SM4_BLOCK_SIZE);
        }
        return ciphertext;
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
        for (int i = 0; i < 32; i++) {
            invRk[i] = rk[31 - i];
        }

        byte[] plaintextPadded = new byte[ciphertext.length];
        byte[] currentIv = new byte[SM4_IV_SIZE];
        System.arraycopy(iv, 0, currentIv, 0, SM4_IV_SIZE);

        byte[] block = new byte[SM4_BLOCK_SIZE];
        for (int offset = 0; offset < ciphertext.length; offset += SM4_BLOCK_SIZE) {
            sm4CryptBlock(ciphertext, offset, block, 0, invRk);
            for (int i = 0; i < SM4_BLOCK_SIZE; i++) {
                plaintextPadded[offset + i] = (byte) (block[i] ^ currentIv[i]);
            }
            System.arraycopy(ciphertext, offset, currentIv, 0, SM4_BLOCK_SIZE);
        }
        return pkcs7Unpad(plaintextPadded);
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
        for (int offset = 0; offset < padded.length; offset += SM4_BLOCK_SIZE) {
            sm4CryptBlock(padded, offset, ciphertext, offset, rk);
        }
        return ciphertext;
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
        for (int i = 0; i < 32; i++) {
            invRk[i] = rk[31 - i];
        }
        byte[] plaintextPadded = new byte[ciphertext.length];
        for (int offset = 0; offset < ciphertext.length; offset += SM4_BLOCK_SIZE) {
            sm4CryptBlock(ciphertext, offset, plaintextPadded, offset, invRk);
        }
        return pkcs7Unpad(plaintextPadded);
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
        if (padLen < 1 || padLen > SM4_BLOCK_SIZE) {
            throw new IllegalArgumentException("PKCS7 解填充失败，非法填充字节: " + padLen);
        }
        for (int i = data.length - padLen; i < data.length; i++) {
            if ((data[i] & 0xff) != padLen) {
                throw new IllegalArgumentException("PKCS7 解填充失败，填充数据不一致");
            }
        }
        byte[] unpadded = new byte[data.length - padLen];
        System.arraycopy(data, 0, unpadded, 0, unpadded.length);
        return unpadded;
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
