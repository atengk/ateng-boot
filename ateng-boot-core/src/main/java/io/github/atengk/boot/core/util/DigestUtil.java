package io.github.atengk.boot.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 纯 JDK 原生消息摘要算法工具类
 * 基于 Zero-Bloat 原则，100% 依托 JDK 17 原生 MessageDigest 与 HexFormat 实现 SHA-256 与 MD5 摘要计算
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class DigestUtil {

    private static final String ALGORITHM_SHA256 = "SHA-256";
    private static final String ALGORITHM_MD5 = "MD5";
    private static final byte[] EMPTY_BYTES = new byte[0];

    private DigestUtil() {
    }

    /**
     * 计算数据的 SHA-256 摘要字节数组
     *
     * @param data 输入数据字节数组
     * @return 32 字节 SHA-256 摘要
     */
    public static byte[] sha256(byte[] data) {
        return digest(ALGORITHM_SHA256, data != null ? data : EMPTY_BYTES);
    }

    /**
     * 计算字符串的 SHA-256 摘要字节数组（UTF-8）
     *
     * @param data 输入字符串
     * @return 32 字节 SHA-256 摘要
     */
    public static byte[] sha256(String data) {
        return sha256(data != null ? data.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES);
    }

    /**
     * 计算数据的 SHA-256 小写十六进制摘要字符串
     *
     * @param data 输入数据字节数组
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256(data));
    }

    /**
     * 计算字符串的 SHA-256 小写十六进制摘要字符串（UTF-8）
     *
     * @param data 输入字符串
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sha256Hex(String data) {
        return HexFormat.of().formatHex(sha256(data));
    }

    /**
     * 计算数据的 MD5 摘要字节数组
     *
     * @param data 输入数据字节数组
     * @return 16 字节 MD5 摘要
     */
    public static byte[] md5(byte[] data) {
        return digest(ALGORITHM_MD5, data != null ? data : EMPTY_BYTES);
    }

    /**
     * 计算字符串的 MD5 摘要字节数组（UTF-8）
     *
     * @param data 输入字符串
     * @return 16 字节 MD5 摘要
     */
    public static byte[] md5(String data) {
        return md5(data != null ? data.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES);
    }

    /**
     * 计算数据的 MD5 小写十六进制摘要字符串
     *
     * @param data 输入数据字节数组
     * @return 32 位小写十六进制摘要字符串
     */
    public static String md5Hex(byte[] data) {
        return HexFormat.of().formatHex(md5(data));
    }

    /**
     * 计算字符串的 MD5 小写十六进制摘要字符串（UTF-8）
     *
     * @param data 输入字符串
     * @return 32 位小写十六进制摘要字符串
     */
    public static String md5Hex(String data) {
        return HexFormat.of().formatHex(md5(data));
    }

    // ==================== 国密 SM3 密码杂凑算法 (GB/T 32905-2016) ====================

    private static final int[] SM3_IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
    };

    /**
     * 计算数据的国密 SM3 摘要字节数组 (GB/T 32905-2016)
     *
     * @param data 输入数据字节数组
     * @return 32 字节 SM3 摘要
     */
    public static byte[] sm3(byte[] data) {
        byte[] input = (data != null) ? data : EMPTY_BYTES;
        long bitLen = (long) input.length * 8;
        int rem = input.length % 64;
        int padLen = (rem < 56) ? (56 - rem) : (120 - rem);
        byte[] padded = new byte[input.length + padLen + 8];
        System.arraycopy(input, 0, padded, 0, input.length);
        padded[input.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) ((bitLen >>> (i * 8)) & 0xff);
        }

        int[] v = SM3_IV.clone();
        int[] w = new int[68];
        int[] wPrime = new int[64];

        for (int offset = 0; offset < padded.length; offset += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = ((padded[offset + i * 4] & 0xff) << 24)
                        | ((padded[offset + i * 4 + 1] & 0xff) << 16)
                        | ((padded[offset + i * 4 + 2] & 0xff) << 8)
                        | (padded[offset + i * 4 + 3] & 0xff);
            }
            for (int i = 16; i < 68; i++) {
                w[i] = sm3P1(w[i - 16] ^ w[i - 9] ^ Integer.rotateLeft(w[i - 3], 15))
                        ^ Integer.rotateLeft(w[i - 13], 7) ^ w[i - 6];
            }
            for (int i = 0; i < 64; i++) {
                wPrime[i] = w[i] ^ w[i + 4];
            }

            int a = v[0], b = v[1], c = v[2], d = v[3];
            int e = v[4], f = v[5], g = v[6], h = v[7];

            for (int j = 0; j < 64; j++) {
                int ss1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(sm3T(j), j % 32), 7);
                int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
                int tt1 = sm3Ff(j, a, b, c) + d + ss2 + wPrime[j];
                int tt2 = sm3Gg(j, e, f, g) + h + ss1 + w[j];
                d = c;
                c = Integer.rotateLeft(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = Integer.rotateLeft(f, 19);
                f = e;
                e = sm3P0(tt2);
            }

            v[0] ^= a;
            v[1] ^= b;
            v[2] ^= c;
            v[3] ^= d;
            v[4] ^= e;
            v[5] ^= f;
            v[6] ^= g;
            v[7] ^= h;
        }

        byte[] result = new byte[32];
        for (int i = 0; i < 8; i++) {
            result[i * 4] = (byte) ((v[i] >>> 24) & 0xff);
            result[i * 4 + 1] = (byte) ((v[i] >>> 16) & 0xff);
            result[i * 4 + 2] = (byte) ((v[i] >>> 8) & 0xff);
            result[i * 4 + 3] = (byte) (v[i] & 0xff);
        }
        return result;
    }

    /**
     * 计算多段字节数组拼接后的国密 SM3 摘要（避免外部多余内存分配）
     *
     * @param parts 多段字节数组
     * @return 32 字节 SM3 摘要
     */
    public static byte[] sm3(byte[]... parts) {
        if (parts == null || parts.length == 0) {
            return sm3(EMPTY_BYTES);
        }
        if (parts.length == 1) {
            return sm3(parts[0]);
        }
        int totalLen = 0;
        for (byte[] p : parts) {
            if (p != null) {
                totalLen += p.length;
            }
        }
        byte[] combined = new byte[totalLen];
        int pos = 0;
        for (byte[] p : parts) {
            if (p != null) {
                System.arraycopy(p, 0, combined, pos, p.length);
                pos += p.length;
            }
        }
        return sm3(combined);
    }

    /**
     * 计算字符串的国密 SM3 摘要字节数组（UTF-8）
     *
     * @param data 输入字符串
     * @return 32 字节 SM3 摘要
     */
    public static byte[] sm3(String data) {
        return sm3(data != null ? data.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES);
    }

    /**
     * 计算数据的国密 SM3 小写十六进制摘要字符串
     *
     * @param data 输入数据字节数组
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sm3Hex(byte[] data) {
        return HexFormat.of().formatHex(sm3(data));
    }

    /**
     * 计算字符串的国密 SM3 小写十六进制摘要字符串（UTF-8）
     *
     * @param data 输入字符串
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sm3Hex(String data) {
        return HexFormat.of().formatHex(sm3(data));
    }

    private static int sm3T(int j) {
        return (j < 16) ? 0x79cc4519 : 0x7a879d8a;
    }

    private static int sm3Ff(int j, int x, int y, int z) {
        return (j < 16) ? (x ^ y ^ z) : ((x & y) | (x & z) | (y & z));
    }

    private static int sm3Gg(int j, int x, int y, int z) {
        return (j < 16) ? (x ^ y ^ z) : ((x & y) | ((~x) & z));
    }

    private static int sm3P0(int x) {
        return x ^ Integer.rotateLeft(x, 9) ^ Integer.rotateLeft(x, 17);
    }

    private static int sm3P1(int x) {
        return x ^ Integer.rotateLeft(x, 15) ^ Integer.rotateLeft(x, 23);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(algorithm);
            return messageDigest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 环境不支持算法: " + algorithm, e);
        }
    }
}
