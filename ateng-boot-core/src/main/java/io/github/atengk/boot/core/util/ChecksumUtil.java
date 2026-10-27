package io.github.atengk.boot.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/**
 * 纯原生数据传输与硬件报文校验和计算工具类
 * 基于 JDK 17 原生 {@link CRC32}、{@link CRC32C} 与 {@link Adler32} 构建，支持字节数组、文本与流式计算
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class ChecksumUtil {

    private static final int BUFFER_SIZE = 8192;

    private ChecksumUtil() {
    }

    /**
     * 计算字节数组的标准 CRC-32 校验和
     *
     * @param data 源数据字节数组
     * @return 32 位无符号整数表示的 CRC-32 校验和
     */
    public static long crc32(byte[] data) {
        Objects.requireNonNull(data, "数据字节数组 data 不能为 null");
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * 计算文本的 CRC-32 校验和（UTF-8）
     *
     * @param data 文本内容
     * @return 32 位校验和
     */
    public static long crc32(String data) {
        Objects.requireNonNull(data, "文本数据 data 不能为 null");
        return crc32(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 流式读取 InputStream 并计算 CRC-32 校验和
     *
     * @param inputStream 输入流
     * @return 32 位校验和
     * @throws IOException IO 读取异常
     */
    public static long crc32(InputStream inputStream) throws IOException {
        return calculateStream(inputStream, new CRC32());
    }

    /**
     * 计算 CRC-32 校验和并返回 8 位小写十六进制字符串（如 "cbf43926"）
     *
     * @param data 源数据字节数组
     * @return 8 位十六进制字符串
     */
    public static String crc32Hex(byte[] data) {
        return formatHex(crc32(data));
    }

    /**
     * 计算 CRC-32 校验和并返回 8 位小写十六进制字符串
     *
     * @param data 文本内容
     * @return 8 位十六进制字符串
     */
    public static String crc32Hex(String data) {
        return formatHex(crc32(data));
    }

    /**
     * 计算字节数组的标准 CRC-32C (Castagnoli) 校验和（现代硬件指令加速）
     *
     * @param data 源数据字节数组
     * @return 32 位无符号校验和
     */
    public static long crc32c(byte[] data) {
        Objects.requireNonNull(data, "数据字节数组 data 不能为 null");
        CRC32C crc = new CRC32C();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * 计算文本的 CRC-32C 校验和（UTF-8）
     *
     * @param data 文本内容
     * @return 32 位校验和
     */
    public static long crc32c(String data) {
        Objects.requireNonNull(data, "文本数据 data 不能为 null");
        return crc32c(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 流式读取 InputStream 并计算 CRC-32C 校验和
     *
     * @param inputStream 输入流
     * @return 32 位校验和
     * @throws IOException IO 读取异常
     */
    public static long crc32c(InputStream inputStream) throws IOException {
        return calculateStream(inputStream, new CRC32C());
    }

    /**
     * 计算 CRC-32C 校验和并返回 8 位小写十六进制字符串
     *
     * @param data 源数据字节数组
     * @return 8 位十六进制字符串
     */
    public static String crc32cHex(byte[] data) {
        return formatHex(crc32c(data));
    }

    /**
     * 计算 CRC-32C 校验和并返回 8 位小写十六进制字符串
     *
     * @param data 文本内容
     * @return 8 位十六进制字符串
     */
    public static String crc32cHex(String data) {
        return formatHex(crc32c(data));
    }

    /**
     * 计算字节数组的标准 Adler-32 校验和
     *
     * @param data 源数据字节数组
     * @return 32 位无符号校验和
     */
    public static long adler32(byte[] data) {
        Objects.requireNonNull(data, "数据字节数组 data 不能为 null");
        Adler32 adler = new Adler32();
        adler.update(data);
        return adler.getValue();
    }

    /**
     * 计算文本的 Adler-32 校验和（UTF-8）
     *
     * @param data 文本内容
     * @return 32 位校验和
     */
    public static long adler32(String data) {
        Objects.requireNonNull(data, "文本数据 data 不能为 null");
        return adler32(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 流式读取 InputStream 并计算 Adler-32 校验和
     *
     * @param inputStream 输入流
     * @return 32 位校验和
     * @throws IOException IO 读取异常
     */
    public static long adler32(InputStream inputStream) throws IOException {
        return calculateStream(inputStream, new Adler32());
    }

    /**
     * 计算 Adler-32 校验和并返回 8 位小写十六进制字符串
     *
     * @param data 源数据字节数组
     * @return 8 位十六进制字符串
     */
    public static String adler32Hex(byte[] data) {
        return formatHex(adler32(data));
    }

    /**
     * 计算 Adler-32 校验和并返回 8 位小写十六进制字符串
     *
     * @param data 文本内容
     * @return 8 位十六进制字符串
     */
    public static String adler32Hex(String data) {
        return formatHex(adler32(data));
    }

    /**
     * 校验数据的 CRC-32 是否与预期一致
     *
     * @param expected 预期校验和数值
     * @param data     实际数据
     * @return 匹配返回 true
     */
    public static boolean verifyCrc32(long expected, byte[] data) {
        return data != null && crc32(data) == expected;
    }

    /**
     * 校验数据的 CRC-32C 是否与预期一致
     *
     * @param expected 预期校验和数值
     * @param data     实际数据
     * @return 匹配返回 true
     */
    public static boolean verifyCrc32c(long expected, byte[] data) {
        return data != null && crc32c(data) == expected;
    }

    /**
     * 校验数据的 Adler-32 是否与预期一致
     *
     * @param expected 预期校验和数值
     * @param data     实际数据
     * @return 匹配返回 true
     */
    public static boolean verifyAdler32(long expected, byte[] data) {
        return data != null && adler32(data) == expected;
    }

    private static long calculateStream(InputStream in, Checksum checksum) throws IOException {
        Objects.requireNonNull(in, "输入流 inputStream 不能为 null");
        byte[] buffer = new byte[BUFFER_SIZE];
        int len;
        while ((len = in.read(buffer)) != -1) {
            checksum.update(buffer, 0, len);
        }
        return checksum.getValue();
    }

    private static String formatHex(long value) {
        return String.format("%08x", value);
    }
}
