package io.github.atengk.boot.mqtt.codec;

import java.util.Arrays;
import java.util.Objects;

/**
 * 工业国标与十六进制二进制报文载荷实体
 * 专供电力（DL/T 645）、汽车国标（GB/T 32960）及两客一危（JT/T 808）等二进制协议交互
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class HexPayload {

    private final byte[] bytes;
    private final String hexString;

    public HexPayload(byte[] bytes) {
        this.bytes = bytes != null ? bytes : new byte[0];
        this.hexString = HexMqttMessageConverter.bytesToHex(this.bytes);
    }

    public HexPayload(String hexString) {
        this.hexString = hexString != null ? hexString.trim() : "";
        this.bytes = HexMqttMessageConverter.hexToBytes(this.hexString);
    }

    public static HexPayload of(byte[] bytes) {
        return new HexPayload(bytes);
    }

    public static HexPayload of(String hexString) {
        return new HexPayload(hexString);
    }

    public byte[] getBytes() {
        return bytes;
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
