package io.github.atengk.boot.core.util;

import java.util.HexFormat;

/**
 * 十六进制编解码高性能工具类
 * 基于 JDK 17 原生 {@link HexFormat} 实现纯原生、零外部依赖的高吞吐 Hex 编码与解码，完整保留前导零与边界字节
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class HexUtil {

    private static final HexFormat LOWER_CASE_HEX_FORMAT = HexFormat.of();
    private static final HexFormat UPPER_CASE_HEX_FORMAT = HexFormat.of().withUpperCase();
    private static final byte[] EMPTY_BYTES = new byte[0];

    private HexUtil() {
    }

    /**
     * 将字节数组编码为小写十六进制字符串
     *
     * @param data 原始二进制字节数组
     * @return 小写十六进制字符串；若 data 为 null 则返回 null，若为空数组返回空串
     */
    public static String encodeHex(byte[] data) {
        return encodeHex(data, true);
    }

    /**
     * 将字节数组编码为指定大小写的十六进制字符串
     *
     * @param data        原始二进制字节数组
     * @param toLowerCase 是否转换为小写（true: 小写；false: 大写）
     * @return 十六进制字符串；若 data 为 null 则返回 null，若为空数组返回空串
     */
    public static String encodeHex(byte[] data, boolean toLowerCase) {
        if (data == null) {
            return null;
        }
        if (data.length == 0) {
            return "";
        }
        HexFormat format = toLowerCase ? LOWER_CASE_HEX_FORMAT : UPPER_CASE_HEX_FORMAT;
        return format.formatHex(data);
    }

    /**
     * 将十六进制字符串解码为原始字节数组
     * 支持大小写混合字符输入，完整保留前导零
     *
     * @param hexString 十六进制字符串
     * @return 解码后的字节数组；若 hexString 为 null 或空串返回空字节数组
     * @throws IllegalArgumentException 当 hexString 包含非十六进制非法字符或长度为奇数时抛出
     */
    public static byte[] decodeHex(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            return EMPTY_BYTES;
        }
        String cleanHex = hexString.trim();
        if (cleanHex.isEmpty()) {
            return EMPTY_BYTES;
        }
        try {
            return LOWER_CASE_HEX_FORMAT.parseHex(cleanHex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的十六进制字符串: [" + hexString + "]", e);
        }
    }
}
