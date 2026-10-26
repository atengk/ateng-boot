package io.github.atengk.boot.tcp.codec;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 载荷编解码处理器嵌入式单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 载荷编解码处理器嵌入式单元测试")
class TcpPayloadCodecHandlerTest {

    @Test
    @DisplayName("验证入站 ByteBuf 自动转译为安全 TcpPayload 并在 finally 中确定性释放直接内存")
    void testInboundDecodeAndMemoryRelease() {
        EmbeddedChannel channel = new EmbeddedChannel(TcpPayloadCodecHandler.INSTANCE);

        ByteBuf rawBuf = Unpooled.buffer();
        rawBuf.writeBytes("Netty-Safe-Buffer".getBytes(StandardCharsets.UTF_8));
        assertThat(rawBuf.refCnt()).isEqualTo(1);

        // 写入入站管道
        channel.writeInbound(rawBuf);

        // 验证读取到的入站消息为安全堆内存 TcpPayload
        TcpPayload payload = channel.readInbound();
        assertThat(payload).isNotNull();
        assertThat(payload.asString()).isEqualTo("Netty-Safe-Buffer");

        // 验证输入的 ByteBuf 已经被确定性 release，引用计数归零（杜绝堆外内存泄漏）
        assertThat(rawBuf.refCnt()).isEqualTo(0);

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证出站 TcpPayload 序列化为 ByteBuf")
    void testOutboundTcpPayload() {
        EmbeddedChannel channel = new EmbeddedChannel(TcpPayloadCodecHandler.INSTANCE);

        TcpPayload payload = TcpPayload.of("Outbound-Payload");
        channel.writeOutbound(payload);

        ByteBuf outBuf = channel.readOutbound();
        assertThat(outBuf).isNotNull();
        assertThat(outBuf.toString(StandardCharsets.UTF_8)).isEqualTo("Outbound-Payload");
        outBuf.release();

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证出站 TcpPacket 序列化为 ByteBuf")
    void testOutboundTcpPacket() {
        EmbeddedChannel channel = new EmbeddedChannel(TcpPayloadCodecHandler.INSTANCE);

        TcpPacket packet = TcpPacket.of("0x01", TcpPayload.of("Outbound-Packet"));
        channel.writeOutbound(packet);

        ByteBuf outBuf = channel.readOutbound();
        assertThat(outBuf).isNotNull();
        assertThat(outBuf.toString(StandardCharsets.UTF_8)).isEqualTo("Outbound-Packet");
        outBuf.release();

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证出站原始 byte[] 与 String 序列化为 ByteBuf")
    void testOutboundPrimitiveTypes() {
        EmbeddedChannel channel = new EmbeddedChannel(TcpPayloadCodecHandler.INSTANCE);

        channel.writeOutbound("Text-Msg");
        ByteBuf buf1 = channel.readOutbound();
        assertThat(buf1.toString(StandardCharsets.UTF_8)).isEqualTo("Text-Msg");
        buf1.release();

        channel.writeOutbound(new byte[]{1, 2, 3});
        ByteBuf buf2 = channel.readOutbound();
        assertThat(buf2.readableBytes()).isEqualTo(3);
        buf2.release();

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证出站 TcpPacket 载荷为 null 时的安全写入与防 NPE")
    void testOutboundTcpPacketWithNullPayload() {
        EmbeddedChannel channel = new EmbeddedChannel(TcpPayloadCodecHandler.INSTANCE);

        TcpPacket packet = TcpPacket.of("HEARTBEAT", "REQ-001", null);
        channel.writeOutbound(packet);

        ByteBuf outBuf = channel.readOutbound();
        assertThat(outBuf).isNotNull();
        assertThat(outBuf.readableBytes()).isEqualTo(0);
        outBuf.release();

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证挂载 DELIMITER_KEY 时出站自动补齐定界符")
    void testOutboundAutoAppendDelimiter() {
        EmbeddedChannel channel = new EmbeddedChannel(TcpPayloadCodecHandler.INSTANCE);
        channel.attr(TcpPayloadCodecHandler.DELIMITER_KEY).set(new byte[]{0x0D, 0x0A});

        channel.writeOutbound("DataWithoutEnding");
        ByteBuf outBuf = channel.readOutbound();
        assertThat(outBuf).isNotNull();
        assertThat(outBuf.toString(StandardCharsets.UTF_8)).isEqualTo("DataWithoutEnding\r\n");
        outBuf.release();

        channel.finishAndReleaseAll();
    }
}
