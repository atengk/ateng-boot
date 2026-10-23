package io.github.atengk.boot.mqtt.codec;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * 工业国标与十六进制二进制报文载荷实体
 * 专供电力（DL/T 645）、汽车国标（GB/T 32960）及两客一危（JT/T 808）等二进制协议交互
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class HexPayload {

    private static final HexFormat HEX_FORMAT = HexFormat.of().withUpperCase();

    private final byte[] bytes;
    private final String hexString;

    public HexPayload(byte[] bytes) {
        this.bytes = bytes != null ? Arrays.copyOf(bytes, bytes.length) : new byte[0];
        this.hexString = HEX_FORMAT.formatHex(this.bytes);
    }

    public HexPayload(String hexString) {
        if (hexString == null || hexString.isBlank()) {
            this.hexString = "";
            this.bytes = new byte[0];
        } else {
            String clean = hexString.replaceAll("\\s+", "").toUpperCase();
            if (clean.length() % 2 != 0) {
                clean = "0" + clean;
            }
            this.hexString = clean;
            this.bytes = HEX_FORMAT.parseHex(clean);
        }
    }

    public static HexPayload of(byte[] bytes) {
        return new HexPayload(bytes);
    }

    public static HexPayload of(String hexString) {
        return new HexPayload(hexString);
    }

    public byte[] getBytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public String getHexString() {
        return hexString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HexPayload that = (HexPayload) o;
        return Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return hexString;
    }
}
