package io.github.atengk.boot.core.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 纯 JDK 原生 RFC 5869 HKDF 密钥派生原语工具类
 * 基于 {@link HmacUtil} 实现标准 {@code HKDF-SHA256} 与国密 {@code HKDF-SM3} 提取-扩展（Extract-and-Expand），
 * 支持按上下文 {@code info} 执行多用途子密钥域隔离派生，所有中间缓冲在 {@code finally} 块确定性抹零
 *
 * @author Ateng
 * @since 2026-11-27
 */
public final class HkdfUtil {

    public static final int SHA256_HASH_LEN = 32;
    public static final int SM3_HASH_LEN = 32;
    public static final int MAX_EXPAND_FACTOR = 255;

    private static final byte[] EMPTY_BYTES = new byte[0];

    private HkdfUtil() {
    }

    /**
     * HKDF-SHA256 提取阶段 (RFC 5869 Section 2.2)
     *
     * @param salt 可选盐值字节数组（为 {@code null} 或空数组时自动填充 32 字节 {@code 0x00}）
     * @param ikm  输入密钥材料（不能为空）
     * @return 32 字节伪随机密钥 PRK
     * @throws IllegalArgumentException {@code ikm} 为空时抛出
     */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        return extractInternal(false, salt, ikm);
    }

    /**
     * HKDF-SHA256 扩展阶段 (RFC 5869 Section 2.3)
     *
     * @param prk    伪随机密钥（长度至少 32 字节）
     * @param info   上下文与用途域隔离标识（可为 {@code null}）
     * @param length 期望输出密钥材料字节长度，范围 {@code [1, 8160]}
     * @return 指定长度的派生输出密钥材料 OKM
     * @throws IllegalArgumentException {@code prk} 长度不足或 {@code length} 越界时抛出
     */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        return expandInternal(false, prk, info, length);
    }

    /**
     * HKDF-SHA256 扩展阶段（UTF-8 上下文域标识重载）
     *
     * @param prk    伪随机密钥（长度至少 32 字节）
     * @param info   上下文与用途域隔离字符串（可为 {@code null}）
     * @param length 期望输出密钥材料字节长度，范围 {@code [1, 8160]}
     * @return 指定长度的派生输出密钥材料 OKM
     */
    public static byte[] expand(byte[] prk, String info, int length) {
        return expand(prk, toUtf8Bytes(info), length);
    }

    /**
     * HKDF-SHA256 一步法密钥派生（Extract-then-Expand）
     *
     * @param ikm    输入密钥材料（不能为空）
     * @param salt   可选盐值（可为 {@code null} 或空数组）
     * @param info   可选上下文域隔离信息（可为 {@code null}）
     * @param length 目标派生字节长度，范围 {@code [1, 8160]}
     * @return 指定长度的派生子密钥
     */
    public static byte[] deriveKey(byte[] ikm, byte[] salt, byte[] info, int length) {
        validateOutputLength(length, SHA256_HASH_LEN);
        byte[] prk = extract(salt, ikm);
        try {
            return expand(prk, info, length);
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    /**
     * HKDF-SHA256 一步法密钥派生（UTF-8 上下文域标识重载）
     *
     * @param ikm    输入密钥材料（不能为空）
     * @param salt   可选盐值（可为 {@code null} 或空数组）
     * @param info   上下文域隔离字符串（可为 {@code null}）
     * @param length 目标派生字节长度，范围 {@code [1, 8160]}
     * @return 指定长度的派生子密钥
     */
    public static byte[] deriveKey(byte[] ikm, byte[] salt, String info, int length) {
        return deriveKey(ikm, salt, toUtf8Bytes(info), length);
    }

    /**
     * 国密 HKDF-SM3 提取阶段 (基于 HmacSM3)
     *
     * @param salt 可选盐值字节数组（为 {@code null} 或空数组时自动填充 32 字节 {@code 0x00}）
     * @param ikm  输入密钥材料（不能为空）
     * @return 32 字节伪随机密钥 PRK
     */
    public static byte[] extractSm3(byte[] salt, byte[] ikm) {
        return extractInternal(true, salt, ikm);
    }

    /**
     * 国密 HKDF-SM3 扩展阶段 (基于 HmacSM3)
     *
     * @param prk    伪随机密钥（长度至少 32 字节）
     * @param info   上下文与用途域隔离标识（可为 {@code null}）
     * @param length 期望输出密钥材料字节长度，范围 {@code [1, 8160]}
     * @return 指定长度的派生输出密钥材料 OKM
     */
    public static byte[] expandSm3(byte[] prk, byte[] info, int length) {
        return expandInternal(true, prk, info, length);
    }

    /**
     * 国密 HKDF-SM3 扩展阶段（UTF-8 上下文域标识重载）
     *
     * @param prk    伪随机密钥（长度至少 32 字节）
     * @param info   上下文与用途域隔离字符串（可为 {@code null}）
     * @param length 期望输出密钥材料字节长度，范围 {@code [1, 8160]}
     * @return 指定长度的派生输出密钥材料 OKM
     */
    public static byte[] expandSm3(byte[] prk, String info, int length) {
        return expandSm3(prk, toUtf8Bytes(info), length);
    }

    /**
     * 国密 HKDF-SM3 一步法密钥派生（Extract-then-Expand）
     *
     * @param ikm    输入密钥材料（不能为空）
     * @param salt   可选盐值（可为 {@code null} 或空数组）
     * @param info   可选上下文域隔离信息（可为 {@code null}）
     * @param length 目标派生字节长度，范围 {@code [1, 8160]}
     * @return 指定长度的派生子密钥
     */
    public static byte[] deriveKeySm3(byte[] ikm, byte[] salt, byte[] info, int length) {
        validateOutputLength(length, SM3_HASH_LEN);
        byte[] prk = extractSm3(salt, ikm);
        try {
            return expandSm3(prk, info, length);
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    /**
     * 国密 HKDF-SM3 一步法密钥派生（UTF-8 上下文域标识重载）
     *
     * @param ikm    输入密钥材料（不能为空）
     * @param salt   可选盐值（可为 {@code null} 或空数组）
     * @param info   上下文域隔离字符串（可为 {@code null}）
     * @param length 目标派生字节长度，范围 {@code [1, 8160]}
     * @return 指定长度的派生子密钥
     */
    public static byte[] deriveKeySm3(byte[] ikm, byte[] salt, String info, int length) {
        return deriveKeySm3(ikm, salt, toUtf8Bytes(info), length);
    }

    private static byte[] extractInternal(boolean sm3, byte[] salt, byte[] ikm) {
        if (ikm == null || ikm.length == 0) {
            throw new IllegalArgumentException("HKDF 输入密钥材料 ikm 不能为空");
        }
        int hashLen = sm3 ? SM3_HASH_LEN : SHA256_HASH_LEN;
        boolean allocatedZeroSalt = (salt == null || salt.length == 0);
        byte[] effectiveSalt = allocatedZeroSalt ? new byte[hashLen] : salt;
        try {
            return sm3
                    ? HmacUtil.hmacSm3(ikm, effectiveSalt)
                    : HmacUtil.hmacSha256(ikm, effectiveSalt);
        } finally {
            if (allocatedZeroSalt) {
                Arrays.fill(effectiveSalt, (byte) 0);
            }
        }
    }

    private static byte[] expandInternal(boolean sm3, byte[] prk, byte[] info, int length) {
        int hashLen = sm3 ? SM3_HASH_LEN : SHA256_HASH_LEN;
        if (prk == null || prk.length < hashLen) {
            throw new IllegalArgumentException("HKDF 伪随机密钥 prk 长度不能小于 " + hashLen + " 字节");
        }
        validateOutputLength(length, hashLen);

        byte[] safeInfo = (info != null) ? info : EMPTY_BYTES;
        byte[] okm = new byte[length];
        byte[] t = EMPTY_BYTES;
        byte[] blockInput = null;
        int offset = 0;
        int counter = 1;

        try {
            while (offset < length) {
                blockInput = new byte[t.length + safeInfo.length + 1];
                if (t.length > 0) {
                    System.arraycopy(t, 0, blockInput, 0, t.length);
                    Arrays.fill(t, (byte) 0);
                }
                if (safeInfo.length > 0) {
                    System.arraycopy(safeInfo, 0, blockInput, t.length, safeInfo.length);
                }
                blockInput[blockInput.length - 1] = (byte) counter;

                t = sm3
                        ? HmacUtil.hmacSm3(blockInput, prk)
                        : HmacUtil.hmacSha256(blockInput, prk);
                Arrays.fill(blockInput, (byte) 0);
                blockInput = null;

                int copyLen = Math.min(hashLen, length - offset);
                System.arraycopy(t, 0, okm, offset, copyLen);
                offset += copyLen;
                counter++;
            }
            return okm;
        } finally {
            if (t.length > 0) {
                Arrays.fill(t, (byte) 0);
            }
            if (blockInput != null) {
                Arrays.fill(blockInput, (byte) 0);
            }
        }
    }

    private static void validateOutputLength(int length, int hashLen) {
        int maxLen = MAX_EXPAND_FACTOR * hashLen;
        if (length < 1 || length > maxLen) {
            throw new IllegalArgumentException(
                    "HKDF 派生长度 length 必须在 [1, " + maxLen + "] 范围内，实际入参: " + length);
        }
    }

    private static byte[] toUtf8Bytes(String info) {
        return (info != null && !info.isEmpty()) ? info.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES;
    }
}
