package io.github.atengk.boot.tcp.util;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;

/**
 * 工业级校验和与报文清洗工具类
 * <p>
 * 内置异或校验（XOR）、CRC16-Modbus、CRC16-CCITT、CRC32 及交通部 JT/T 808 国标转义清洗原语。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class ChecksumUtil {

    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    private ChecksumUtil() {
    }

    /**
     * 计算数据块的 XOR 异或校验码（全字节逐位异或，高频用于 JT/T 808、环保 HJ 212 等国标协议）
     *
     * @param data 原始字节数组
     * @return 1 字节异或值
     */
    public static byte calculateXor(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        return calculateXor(data, 0, data.length);
    }

    /**
     * 计算指定区间数据块的 XOR 异或校验码
     *
     * @param data   原始字节数组
     * @param offset 起始偏移量
     * @param length 校验字节长度
     * @return 1 字节异或值
     */
    public static byte calculateXor(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return 0;
        }
        byte xor = 0;
        int end = Math.min(offset + length, data.length);
        for (int i = offset; i < end; i++) {
            xor ^= data[i];
        }
        return xor;
    }

    /**
     * 验证指定区间数据块的 XOR 异或校验码是否与预期值一致
     *
     * @param data     原始字节数组
     * @param offset   起始偏移量
     * @param length   校验字节长度
     * @param expected 预期异或值
     * @return 验证是否通过
     */
    public static boolean verifyXor(byte[] data, int offset, int length, byte expected) {
        return calculateXor(data, offset, length) == expected;
    }

    /**
     * 计算 CRC16-Modbus 校验码
     * <p>
     * 多项式: 0xA001，初始值: 0xFFFF
     * </p>
     *
     * @param data 原始字节数组
     * @return 16位整数校验码
     */
    public static int calculateCrc16Modbus(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        return calculateCrc16Modbus(data, 0, data.length);
    }

    /**
     * 计算指定区间的 CRC16-Modbus 校验码
     *
     * @param data   原始字节数组
     * @param offset 起始偏移量
     * @param length 校验字节长度
     * @return 16位整数校验码
     */
    public static int calculateCrc16Modbus(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return 0;
        }
        int crc = 0xFFFF;
        int end = Math.min(offset + length, data.length);
        for (int i = offset; i < end; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc = crc >> 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    /**
     * 验证 CRC16-Modbus 校验码
     *
     * @param data     原始字节数组
     * @param offset   起始偏移量
     * @param length   校验字节长度
     * @param expected 预期校验码
     * @return 验证是否通过
     */
    public static boolean verifyCrc16Modbus(byte[] data, int offset, int length, int expected) {
        return calculateCrc16Modbus(data, offset, length) == (expected & 0xFFFF);
    }

    /**
     * 计算 CRC16-CCITT (False) 校验码
     * <p>
     * 多项式: 0x1021，初始值: 0xFFFF
     * </p>
     *
     * @param data 原始字节数组
     * @return 16位整数校验码
     */
    public static int calculateCrc16Ccitt(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        return calculateCrc16Ccitt(data, 0, data.length);
    }

    /**
     * 计算指定区间的 CRC16-CCITT (False) 校验码
     *
     * @param data   原始字节数组
     * @param offset 起始偏移量
     * @param length 校验字节长度
     * @return 16位整数校验码
     */
    public static int calculateCrc16Ccitt(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return 0;
        }
        int crc = 0xFFFF;
        int end = Math.min(offset + length, data.length);
        for (int i = offset; i < end; i++) {
            crc = ((crc << 8) & 0xFFFF) ^ crc16CcittTable()[(crc >> 8) ^ (data[i] & 0xFF)];
        }
        return crc & 0xFFFF;
    }

    /**
     * 验证 CRC16-CCITT 校验码
     *
     * @param data     原始字节数组
     * @param offset   起始偏移量
     * @param length   校验字节长度
     * @param expected 预期校验码
     * @return 验证是否通过
     */
    public static boolean verifyCrc16Ccitt(byte[] data, int offset, int length, int expected) {
        return calculateCrc16Ccitt(data, offset, length) == (expected & 0xFFFF);
    }

    /**
     * 计算 CRC32 校验和
     *
     * @param data 原始字节数组
     * @return 32位无符号校验和
     */
    public static long calculateCrc32(byte[] data) {
        if (data == null || data.length == 0) {
            return 0L;
        }
        return calculateCrc32(data, 0, data.length);
    }

    /**
     * 计算指定区间的 CRC32 校验和
     *
     * @param data   原始字节数组
     * @param offset 起始偏移量
     * @param length 校验字节长度
     * @return 32位无符号校验和
     */
    public static long calculateCrc32(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return 0L;
        }
        CRC32 crc32 = new CRC32();
        crc32.update(data, offset, Math.min(length, data.length - offset));
        return crc32.getValue();
    }

    /**
     * 验证 CRC32 校验和
     *
     * @param data     原始字节数组
     * @param offset   起始偏移量
     * @param length   校验字节长度
     * @param expected 预期校验码
     * @return 验证是否通过
     */
    public static boolean verifyCrc32(byte[] data, int offset, int length, long expected) {
        return calculateCrc32(data, offset, length) == expected;
    }

    /**
     * 交通部 JT/T 808 报文还原反转义
     * <p>
     * 规则: 0x7d 0x02 -> 0x7e, 0x7d 0x01 -> 0x7d
     * </p>
     *
     * @param data 转义前的原始报文数据体
     * @return 还原后的明文数据体
     */
    public static byte[] unescapeJt808(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0x7D && i + 1 < data.length) {
                if (data[i + 1] == 0x02) {
                    out.write(0x7E);
                    i++;
                    continue;
                } else if (data[i + 1] == 0x01) {
                    out.write(0x7D);
                    i++;
                    continue;
                }
            }
            out.write(data[i]);
        }
        return out.toByteArray();
    }

    /**
     * 交通部 JT/T 808 报文发送前转义
     * <p>
     * 规则: 0x7e -> 0x7d 0x02, 0x7d -> 0x7d 0x01
     * </p>
     *
     * @param data 待发送的原始报文数据体（不含头尾 0x7e 标识符）
     * @return 转义后的报文数据体
     */
    public static byte[] escapeJt808(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 8);
        for (byte b : data) {
            if (b == 0x7E) {
                out.write(0x7D);
                out.write(0x02);
            } else if (b == 0x7D) {
                out.write(0x7D);
                out.write(0x01);
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    /**
     * 字节数组转大写十六进制字符串（如 {0x7E, 0x01} -> "7E01"）
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    public static String toHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }

    /**
     * 十六进制字符串转字节数组（如 "7E01" -> {0x7E, 0x01}，支持以 "0x" 或 "0X" 开头）
     *
     * @param hexString 十六进制字符串
     * @return 字节数组
     */
    public static byte[] fromHexString(String hexString) {
        if (hexString == null || hexString.trim().isEmpty()) {
            return new byte[0];
        }
        String clean = hexString.trim();
        if (clean.startsWith("0x") || clean.startsWith("0X")) {
            clean = clean.substring(2);
        }
        if (clean.length() % 2 != 0) {
            clean = "0" + clean;
        }
        int len = clean.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(clean.charAt(i), 16);
            int low = Character.digit(clean.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("非法的十六进制字符序列: " + hexString);
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }

    private static int[] crc16CcittTable() {
        return Crc16CcittTableHolder.TABLE;
    }

    private static class Crc16CcittTableHolder {
        private static final int[] TABLE = new int[256];

        static {
            for (int i = 0; i < 256; i++) {
                int curr = i << 8;
                for (int j = 0; j < 8; j++) {
                    if ((curr & 0x8000) != 0) {
                        curr = ((curr << 1) & 0xFFFF) ^ 0x1021;
                    } else {
                        curr = (curr << 1) & 0xFFFF;
                    }
                }
                TABLE[i] = curr & 0xFFFF;
            }
        }
    }
}
