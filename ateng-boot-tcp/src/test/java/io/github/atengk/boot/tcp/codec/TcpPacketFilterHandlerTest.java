package io.github.atengk.boot.tcp.codec;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.github.atengk.boot.tcp.util.ChecksumUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 报文过滤器流水线单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 报文过滤器流水线单元测试")
class TcpPacketFilterHandlerTest {

    @Test
    @DisplayName("验证入站校验和过滤器：校验失败拦截丢弃，校验成功放行")
    void testInboundChecksumFilter() {
        // 创建一个基于 XOR 尾字节校验的工业过滤器示例
        TcpPacketFilter xorFilter = new TcpPacketFilter() {
            @Override
            public boolean filterInbound(TcpPacket packet, ChannelHandlerContext ctx) {
                byte[] bytes = packet.getPayload().asBytes();
                if (bytes.length < 2) {
                    return false;
                }
                // 最后一个字节为校验码，前面的字节为数据体
                int dataLen = bytes.length - 1;
                byte expectedXor = bytes[dataLen];
                return ChecksumUtil.verifyXor(bytes, 0, dataLen, expectedXor);
            }
        };

        TcpPacketFilterHandler handler = new TcpPacketFilterHandler(List.of(xorFilter));
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 1. 发送合法的报文: [0x01, 0x02, 0x03] -> 1 ^ 2 = 3
        byte[] valid = new byte[]{0x01, 0x02, 0x03};
        channel.writeInbound(TcpPacket.of(TcpPayload.of(valid)));
        TcpPacket passed = channel.readInbound();
        assertThat(passed).isNotNull();
        assertThat(passed.getPayload().asBytes()).containsExactly(valid);

        // 2. 发送校验和错误的脏报文: [0x01, 0x02, 0x99]
        byte[] invalid = new byte[]{0x01, 0x02, (byte) 0x99};
        channel.writeInbound(TcpPacket.of(TcpPayload.of(invalid)));
        // 应该被拦截丢弃
        assertThat((TcpPacket) channel.readInbound()).isNull();

        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("验证出站过滤器加工与包装")
    void testOutboundFilter() {
        TcpPacketFilter appendHeaderFilter = new TcpPacketFilter() {
            @Override
            public TcpPacket filterOutbound(TcpPacket packet, ChannelHandlerContext ctx) {
                packet.setHeader("traceTag", "TAG-999");
                return packet;
            }
        };

        TcpPacketFilterHandler handler = new TcpPacketFilterHandler(List.of(appendHeaderFilter));
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        TcpPacket packet = TcpPacket.of("CMD-1", TcpPayload.of("hello"));
        channel.writeOutbound(packet);

        TcpPacket out = channel.readOutbound();
        assertThat(out).isNotNull();
        assertThat(out.getHeader("traceTag", String.class)).isEqualTo("TAG-999");

        channel.finishAndReleaseAll();
    }
}
