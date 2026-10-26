package io.github.atengk.boot.tcp.codec;

import io.github.atengk.boot.tcp.enums.FramingMode;
import io.github.atengk.boot.tcp.properties.TcpFramingProperties;
import io.github.atengk.boot.tcp.properties.TcpLengthFieldProperties;
import io.github.atengk.boot.tcp.util.ChecksumUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.nio.charset.StandardCharsets;

/**
 * TCP 分帧解码器工程类
 * <p>
 * 根据 {@link TcpFramingProperties} 配置动态创建对应的 Netty 粘包拆包解码器。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class TcpFramingDecoderFactory {

    private TcpFramingDecoderFactory() {
    }

    /**
     * 根据配置构建分帧解码器
     *
     * @param properties 分帧配置属性
     * @return Netty ChannelHandler（若为 NONE 则返回 null）
     */
    public static ChannelHandler createFrameDecoder(TcpFramingProperties properties) {
        if (properties == null || properties.getMode() == null || properties.getMode() == FramingMode.NONE) {
            return null;
        }

        int maxFrameLength = (int) properties.getMaxFrameLength().toBytes();
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException("maxFrameLength 必须大于 0");
        }

        switch (properties.getMode()) {
            case DELIMITER:
                return createDelimiterDecoder(properties, maxFrameLength);
            case FIXED_LENGTH:
                return createFixedLengthDecoder(properties, maxFrameLength);
            case LENGTH_FIELD:
                return createLengthFieldDecoder(properties, maxFrameLength);
            default:
                return null;
        }
    }

    public static byte[] resolveDelimiterBytes(TcpFramingProperties properties) {
        if (properties == null) {
            return "\r\n".getBytes(StandardCharsets.UTF_8);
        }
        String delimiter = properties.getDelimiter();
        if (delimiter == null || delimiter.isEmpty()) {
            return "\r\n".getBytes(StandardCharsets.UTF_8);
        }
        if (delimiter.startsWith("0x") || delimiter.startsWith("0X")) {
            return ChecksumUtil.fromHexString(delimiter);
        }
        String unescaped = delimiter
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\0", "\0");
        return unescaped.getBytes(StandardCharsets.UTF_8);
    }

    private static ChannelHandler createDelimiterDecoder(TcpFramingProperties properties, int maxFrameLength) {
        byte[] delimiterBytes = resolveDelimiterBytes(properties);
        ByteBuf delimBuf = Unpooled.copiedBuffer(delimiterBytes);
        return new DelimiterBasedFrameDecoder(maxFrameLength, properties.isStripDelimiter(), delimBuf);
    }

    private static ChannelHandler createFixedLengthDecoder(TcpFramingProperties properties, int maxFrameLength) {
        int fixedLength = properties.getFixedLength();
        if (fixedLength <= 0) {
            throw new IllegalArgumentException("fixedLength 必须大于 0: " + fixedLength);
        }
        if (fixedLength > maxFrameLength) {
            throw new IllegalArgumentException("fixedLength (" + fixedLength + ") 不能大于 maxFrameLength (" + maxFrameLength + ")");
        }
        return new FixedLengthFrameDecoder(fixedLength);
    }

    private static ChannelHandler createLengthFieldDecoder(TcpFramingProperties properties, int maxFrameLength) {
        TcpLengthFieldProperties lf = properties.getLengthField();
        if (lf == null) {
            lf = new TcpLengthFieldProperties();
        }
        return new LengthFieldBasedFrameDecoder(
                maxFrameLength,
                lf.getOffset(),
                lf.getLength(),
                lf.getAdjustment(),
                lf.getInitialBytesToStrip()
        );
    }
}
