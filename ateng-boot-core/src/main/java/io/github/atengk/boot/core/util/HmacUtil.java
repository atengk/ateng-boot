package io.github.atengk.boot.core.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/**
 * 纯 JDK 17 原生安全散列消息认证码（HMAC）工具类
 * 提供标准 HMAC-SHA256、HMAC-SHA1 与 HMAC-MD5 密钥加签，并内置恒定时间安全比对防侧信道时序攻击
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class HmacUtil {

    public static final String HMAC_SHA256 = "HmacSHA256";
    public static final String HMAC_SHA1 = "HmacSHA1";
    public static final String HMAC_MD5 = "HmacMD5";

    private HmacUtil() {
    }

    /**
     * 基于指定算法对数据进行 HMAC 加签
     *
     * @param algorithm HMAC 算法名称（如 HmacSHA256、HmacSHA1、HmacMD5）
     * @param data      待签名源数据
     * @param key       签名密钥字节数组
     * @return 签名结果字节数组
     */
    public static byte[] hmac(String algorithm, byte[] data, byte[] key) {
        Objects.requireNonNull(algorithm, "HMAC 算法 algorithm 不能为 null");
        Objects.requireNonNull(data, "待签名数据 data 不能为 null");
        Objects.requireNonNull(key, "签名密钥 key 不能为 null");
        try {
            Mac mac = Mac.getInstance(algorithm);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key, algorithm);
            mac.init(secretKeySpec);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("HMAC 签名计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 HMAC-SHA256 算法生成签名
     *
     * @param data 待签名源数据
     * @param key  签名密钥字节数组
     * @return 签名字节数组
     */
    public static byte[] hmacSha256(byte[] data, byte[] key) {
        return hmac(HMAC_SHA256, data, key);
    }

    /**
     * 使用 HMAC-SHA256 算法生成十六进制小写格式签名字符串
     *
     * @param data 待签名源数据
     * @param key  签名密钥字节数组
     * @return 64 位十六进制签名字符串
     */
    public static String hmacSha256Hex(byte[] data, byte[] key) {
        return HexUtil.encodeHex(hmacSha256(data, key));
    }

    /**
     * 使用 HMAC-SHA256 算法生成十六进制小写格式签名字符串
     *
     * @param data 待签名文本（UTF-8）
     * @param key  签名密钥文本（UTF-8）
     * @return 64 位十六进制签名字符串
     */
    public static String hmacSha256Hex(String data, String key) {
        Objects.requireNonNull(data, "待签名文本 data 不能为 null");
        Objects.requireNonNull(key, "签名密钥文本 key 不能为 null");
        return hmacSha256Hex(data.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 使用 HMAC-SHA256 算法生成 Base64 格式签名字符串
     *
     * @param data 待签名源数据
     * @param key  签名密钥字节数组
     * @return Base64 编码签名字符串
     */
    public static String hmacSha256Base64(byte[] data, byte[] key) {
        return Base64.getEncoder().encodeToString(hmacSha256(data, key));
    }

    /**
     * 使用 HMAC-SHA256 算法生成 Base64 格式签名字符串
     *
     * @param data 待签名文本（UTF-8）
     * @param key  签名密钥文本（UTF-8）
     * @return Base64 编码签名字符串
     */
    public static String hmacSha256Base64(String data, String key) {
        Objects.requireNonNull(data, "待签名文本 data 不能为 null");
        Objects.requireNonNull(key, "签名密钥文本 key 不能为 null");
        return hmacSha256Base64(data.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 使用 HMAC-SHA1 算法生成签名
     *
     * @param data 待签名源数据
     * @param key  签名密钥字节数组
     * @return 签名字节数组
     */
    public static byte[] hmacSha1(byte[] data, byte[] key) {
        return hmac(HMAC_SHA1, data, key);
    }

    /**
     * 使用 HMAC-SHA1 算法生成十六进制小写格式签名字符串
     *
     * @param data 待签名源数据
     * @param key  签名密钥字节数组
     * @return 40 位十六进制签名字符串
     */
    public static String hmacSha1Hex(byte[] data, byte[] key) {
        return HexUtil.encodeHex(hmacSha1(data, key));
    }

    /**
     * 使用 HMAC-SHA1 算法生成十六进制小写格式签名字符串
     *
     * @param data 待签名文本（UTF-8）
     * @param key  签名密钥文本（UTF-8）
     * @return 40 位十六进制签名字符串
     */
    public static String hmacSha1Hex(String data, String key) {
        Objects.requireNonNull(data, "待签名文本 data 不能为 null");
        Objects.requireNonNull(key, "签名密钥文本 key 不能为 null");
        return hmacSha1Hex(data.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 使用 HMAC-SHA1 算法生成 Base64 格式签名字符串
     *
     * @param data 待签名文本（UTF-8）
     * @param key  签名密钥文本（UTF-8）
     * @return Base64 编码签名字符串
     */
    public static String hmacSha1Base64(String data, String key) {
        Objects.requireNonNull(data, "待签名文本 data 不能为 null");
        Objects.requireNonNull(key, "签名密钥文本 key 不能为 null");
        byte[] sign = hmacSha1(data.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sign);
    }

    /**
     * 使用 HMAC-MD5 算法生成签名
     *
     * @param data 待签名源数据
     * @param key  签名密钥字节数组
     * @return 签名字节数组
     */
    public static byte[] hmacMd5(byte[] data, byte[] key) {
        return hmac(HMAC_MD5, data, key);
    }

    /**
     * 使用 HMAC-MD5 算法生成十六进制小写格式签名字符串
     *
     * @param data 待签名源数据
     * @param key  签名密钥字节数组
     * @return 32 位十六进制签名字符串
     */
    public static String hmacMd5Hex(byte[] data, byte[] key) {
        return HexUtil.encodeHex(hmacMd5(data, key));
    }

    /**
     * 使用 HMAC-MD5 算法生成十六进制小写格式签名字符串
     *
     * @param data 待签名文本（UTF-8）
     * @param key  签名密钥文本（UTF-8）
     * @return 32 位十六进制签名字符串
     */
    public static String hmacMd5Hex(String data, String key) {
        Objects.requireNonNull(data, "待签名文本 data 不能为 null");
        Objects.requireNonNull(key, "签名密钥文本 key 不能为 null");
        return hmacMd5Hex(data.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 使用 HMAC-MD5 算法生成 Base64 格式签名字符串
     *
     * @param data 待签名文本（UTF-8）
     * @param key  签名密钥文本（UTF-8）
     * @return Base64 编码签名字符串
     */
    public static String hmacMd5Base64(String data, String key) {
        Objects.requireNonNull(data, "待签名文本 data 不能为 null");
        Objects.requireNonNull(key, "签名密钥文本 key 不能为 null");
        byte[] sign = hmacMd5(data.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sign);
    }

    /**
     * 恒定时间安全比对两个字节数组（防侧信道时序攻击）
     *
     * @param expected 预期签名
     * @param actual   实际签名
     * @return 若完全一致返回 true，任一为 null 或内容不匹配返回 false
     */
    public static boolean verify(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 恒定时间安全比对两个十六进制签名字符串
     *
     * @param expectedHex 预期十六进制签名
     * @param actualHex   实际十六进制签名
     * @return 若完全一致返回 true
     */
    public static boolean verifyHex(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        return verify(
                expectedHex.toLowerCase().getBytes(StandardCharsets.UTF_8),
                actualHex.toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 恒定时间安全比对两个 Base64 签名字符串
     *
     * @param expectedBase64 预期 Base64 签名
     * @param actualBase64   实际 Base64 签名
     * @return 若完全一致返回 true
     */
    public static boolean verifyBase64(String expectedBase64, String actualBase64) {
        if (expectedBase64 == null || actualBase64 == null) {
            return false;
        }
        return verify(
                expectedBase64.getBytes(StandardCharsets.UTF_8),
                actualBase64.getBytes(StandardCharsets.UTF_8)
        );
    }
}
