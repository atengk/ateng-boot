package io.github.atengk.boot.tcp.codec;

import io.github.atengk.boot.tcp.enums.FramingMode;
import io.github.atengk.boot.tcp.properties.TcpFramingProperties;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCP 分帧解码器工厂嵌入式单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 分帧解码器工厂嵌入式单元测试")
class TcpFramingDecoderFactoryTest {

    @Test
    @DisplayName("验证 DELIMITER 换行符模式下的半包与粘包切片")
    void testDelimiterFraming() {
        TcpFramingProperties properties = new TcpFramingProperties();
        properties.setMode(FramingMode.DELIMITER);
        properties.setDelimiter("\r\n");
        properties.setStripDelimiter(true);

        ChannelHandler handler = TcpFramingDecoderFactory.createFrameDecoder(properties);
        assertThat(handler).isNotNull();

        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 模拟半包写入
        channel.writeInbound(Unpooled.copiedBuffer("Hello ", StandardCharsets.UTF_8));
        assertThat((ByteBuf) channel.readInbound()).isNull();

        // 写入补齐与分隔符
        channel.writeInbound(Unpooled.copiedBuffer("TCP\r\n", StandardCharsets.UTF_8));
        ByteBuf frame = channel.readInbound();
        assertThat(frame).isNotNull();
        assertThat(frame.toString(StandardCharsets.UTF_8)).isEqualTo("Hello TCP");
        frame.release();

        // 模拟粘包写入（一次写入两个完整帧）
        channel.writeInbound(Unpooled.copiedBuffer("Frame1\r\nFrame2\r\n", StandardCharsets.UTF_8));
        ByteBuf f1 = channel.readInbound();
        ByteBuf f2 = channel.readInbound();
        assertThat(f1.toString(StandardCharsets.UTF_8)).isEqualTo("Frame1");
        assertThat(f2.toString(StandardCharsets.UTF_8)).isEqualTo("Frame2");
        f1.release();
        f2.release();

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证 DELIMITER 十六进制标记模式分帧")
    void testHexDelimiterFraming() {
        TcpFramingProperties properties = new TcpFramingProperties();
        properties.setMode(FramingMode.DELIMITER);
        properties.setDelimiter("0x7E");
        properties.setStripDelimiter(true);

        ChannelHandler handler = TcpFramingDecoderFactory.createFrameDecoder(properties);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        byte[] packet = new byte[]{0x01, 0x02, 0x03, 0x7E};
        channel.writeInbound(Unpooled.copiedBuffer(packet));

        ByteBuf frame = channel.readInbound();
        assertThat(frame).isNotNull();
        assertThat(frame.readableBytes()).isEqualTo(3);
        byte[] read = new byte[3];
        frame.readBytes(read);
        assertThat(read).containsExactly(0x01, 0x02, 0x03);
        frame.release();

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证 FIXED_LENGTH 固定长度分帧模式")
    void testFixedLengthFraming() {
        TcpFramingProperties properties = new TcpFramingProperties();
        properties.setMode(FramingMode.FIXED_LENGTH);
        properties.setFixedLength(4);

        ChannelHandler handler = TcpFramingDecoderFactory.createFrameDecoder(properties);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 写入 10 字节: 应切出两个 4 字节帧，余 2 字节缓冲
        byte[] bytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        channel.writeInbound(Unpooled.copiedBuffer(bytes));

        ByteBuf f1 = channel.readInbound();
        ByteBuf f2 = channel.readInbound();
        assertThat((ByteBuf) channel.readInbound()).isNull();

        assertThat(f1.readableBytes()).isEqualTo(4);
        assertThat(f2.readableBytes()).isEqualTo(4);
        f1.release();
        f2.release();

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证 LENGTH_FIELD 长度字段偏移模式分帧")
    void testLengthFieldFraming() {
        TcpFramingProperties properties = new TcpFramingProperties();
        properties.setMode(FramingMode.LENGTH_FIELD);
        properties.getLengthField().setOffset(0);
        properties.getLengthField().setLength(4);
        properties.getLengthField().setAdjustment(0);
        properties.getLengthField().setInitialBytesToStrip(4); // 剥离 4 字节长度头

        ChannelHandler handler = TcpFramingDecoderFactory.createFrameDecoder(properties);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 构建报文: 4 字节长度 (5) + 5 字节体 ("HELLO")
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(5);
        buf.writeBytes("HELLO".getBytes(StandardCharsets.UTF_8));

        channel.writeInbound(buf);
        ByteBuf frame = channel.readInbound();
        assertThat(frame).isNotNull();
        assertThat(frame.toString(StandardCharsets.UTF_8)).isEqualTo("HELLO");
        frame.release();

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证超过 maxFrameLength 时抛出 TooLongFrameException 截断防爆")
    void testMaxFrameLengthOverflow() {
        TcpFramingProperties properties = new TcpFramingProperties();
        properties.setMode(FramingMode.DELIMITER);
        properties.setDelimiter("\r\n");
        properties.setMaxFrameLength(DataSize.ofBytes(10)); // 最大仅允许 10 字节

        ChannelHandler handler = TcpFramingDecoderFactory.createFrameDecoder(properties);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 写入超过 10 字节的长报文
        byte[] oversized = "123456789012345\r\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> channel.writeInbound(Unpooled.copiedBuffer(oversized)))
                .isInstanceOf(TooLongFrameException.class);

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证 NONE 模式返回空处理器")
    void testNoneMode() {
        TcpFramingProperties properties = new TcpFramingProperties();
        properties.setMode(FramingMode.NONE);
        assertThat(TcpFramingDecoderFactory.createFrameDecoder(properties)).isNull();
    }
}
