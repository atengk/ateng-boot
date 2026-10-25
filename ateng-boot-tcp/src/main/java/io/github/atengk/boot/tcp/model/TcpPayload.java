package io.github.atengk.boot.tcp.model;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.util.ChecksumUtil;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * TCP 领域安全通信载荷实体
 * <p>
 * 封装已转译为安全堆内存的不可变字节数组，提供 HEX、字符串与 JSON 强类型的安全转换原语。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class TcpPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final TcpPayload EMPTY = new TcpPayload(EMPTY_BYTES);

    private final byte[] data;

    private TcpPayload(byte[] data) {
        this.data = data != null ? data : EMPTY_BYTES;
    }

    /**
     * 构建空的 TCP 载荷对象
     *
     * @return 空载荷实例
     */
    public static TcpPayload empty() {
        return EMPTY;
    }

    /**
     * 从原始字节数组构建安全载荷对象（执行防御性拷贝）
     *
     * @param bytes 字节数组
     * @return 载荷实例
     */
    public static TcpPayload of(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return EMPTY;
        }
        return new TcpPayload(bytes.clone());
    }

    /**
     * 从 UTF-8 文本构建载荷对象
     *
     * @param text 文本字符串
     * @return 载荷实例
     */
    public static TcpPayload of(String text) {
        return of(text, StandardCharsets.UTF_8);
    }

    /**
     * 从指定字符集文本构建载荷对象
     *
     * @param text    文本字符串
     * @param charset 字符集编码
     * @return 载荷实例
     */
    public static TcpPayload of(String text, Charset charset) {
        if (text == null) {
            return EMPTY;
        }
        Charset cs = charset != null ? charset : StandardCharsets.UTF_8;
        return new TcpPayload(text.getBytes(cs));
    }

    /**
     * 从十六进制字符串构建载荷对象（如 "7E01027E"）
     *
     * @param hex 十六进制字符串
     * @return 载荷实例
     */
    public static TcpPayload fromHex(String hex) {
        if (hex == null || hex.trim().isEmpty()) {
            return EMPTY;
        }
        return new TcpPayload(ChecksumUtil.fromHexString(hex));
    }

    /**
     * 将业务对象序列化为 JSON 载荷
     *
     * @param object 业务对象
     * @return 载荷实例
     */
    public static TcpPayload fromJson(Object object) {
        if (object == null) {
            return EMPTY;
        }
        String json = JsonUtil.toJsonString(object);
        return of(json);
    }

    /**
     * 获取安全防御性拷贝后的原始字节数组
     *
     * @return 字节数组
     */
    public byte[] asBytes() {
        return data.clone();
    }

    /**
     * 获取载荷字节数组
     *
     * @return 字节数组
     */
    public byte[] getBytes() {
        return asBytes();
    }

    /**
     * 转换为大写十六进制字符串表示
     *
     * @return 十六进制字符串
     */
    public String asHex() {
        return ChecksumUtil.toHexString(data);
    }

    /**
     * 转换为 UTF-8 文本字符串
     *
     * @return 文本内容
     */
    public String asString() {
        return asString(StandardCharsets.UTF_8);
    }

    /**
     * 转换为指定编码的文本字符串
     *
     * @param charset 字符集编码
     * @return 文本内容
     */
    public String asString(Charset charset) {
        Charset cs = charset != null ? charset : StandardCharsets.UTF_8;
        return new String(data, cs);
    }

    /**
     * 反序列化为强类型 JSON 实体对象
     *
     * @param targetClass 目标类型 Class
     * @param <T>         泛型对象
     * @return 反序列化实体
     */
    public <T> T asJson(Class<T> targetClass) {
        if (data.length == 0) {
            return null;
        }
        return JsonUtil.parseObject(asString(), targetClass);
    }

    /**
     * 获取载荷字节大小
     *
     * @return 字节数
     */
    public int length() {
        return data.length;
    }

    /**
     * 载荷是否为空
     *
     * @return true 为空
     */
    public boolean isEmpty() {
        return data.length == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TcpPayload)) return false;
        TcpPayload that = (TcpPayload) o;
        return Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        if (data.length == 0) {
            return "TcpPayload[empty]";
        }
        if (data.length <= 64) {
            return "TcpPayload[len=" + data.length + ", hex=" + asHex() + "]";
        }
        return "TcpPayload[len=" + data.length + ", hexPrefix=" + ChecksumUtil.toHexString(Arrays.copyOf(data, 16)) + "...]";
    }
}
