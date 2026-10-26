package io.github.atengk.boot.tcp.route;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 基于固定二进制偏移量与字节长度的命令字提取器
 * <p>
 * 广泛适用于工业物联网（如 Modbus 功能码、JT808 消息 ID、私有二进制帧头等）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class BinaryOffsetRouteKeyExtractor implements TcpRouteKeyExtractor {

    /**
     * 提取结果编码格式枚举
     */
    public enum Format {
        /**
         * 十六进制大写字符串，例如 {@code "01"}, {@code "0201"}
         */
        HEX,

        /**
         * 带 0x 前缀的十六进制大写字符串，例如 {@code "0x01"}, {@code "0x0201"}
         */
        HEX_PREFIX,

        /**
         * 转为大端整数数字字符串，例如 {@code "1"}, {@code "513"}
         */
        INTEGER,

        /**
         * ASCII 文本，例如 {@code "PING"}
         */
        ASCII
    }

    private final int offset;
    private final int length;
    private final Format format;

    public BinaryOffsetRouteKeyExtractor(int offset, int length) {
        this(offset, length, Format.HEX);
    }

    public BinaryOffsetRouteKeyExtractor(int offset, int length, Format format) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset cannot be negative: " + offset);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive: " + length);
        }
        this.offset = offset;
        this.length = length;
        this.format = format != null ? format : Format.HEX;
    }

    @Override
    public String extract(TcpPacket packet) {
        if (packet == null) {
            return null;
        }
        if (StringUtils.hasText(packet.getCommand())) {
            return packet.getCommand();
        }
        TcpPayload payload = packet.getPayload();
        if (payload == null || payload.isEmpty()) {
            return null;
        }

        byte[] raw = payload.asBytes();
        if (raw == null || raw.length < offset + length) {
            return null;
        }

        byte[] slice = new byte[length];
        System.arraycopy(raw, offset, slice, 0, length);

        return switch (format) {
            case HEX -> toHex(slice, false);
            case HEX_PREFIX -> toHex(slice, true);
            case INTEGER -> String.valueOf(toInteger(slice));
            case ASCII -> new String(slice, StandardCharsets.US_ASCII).trim();
        };
    }

    private static String toHex(byte[] bytes, boolean withPrefix) {
        StringBuilder sb = new StringBuilder(withPrefix ? "0x" : "");
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static long toInteger(byte[] bytes) {
        long result = 0;
        for (byte b : bytes) {
            result = (result << 8) | (b & 0xFFL);
        }
        return result;
    }
}
