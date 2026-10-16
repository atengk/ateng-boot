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

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(algorithm);
            return messageDigest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 环境不支持算法: " + algorithm, e);
        }
    }
}
