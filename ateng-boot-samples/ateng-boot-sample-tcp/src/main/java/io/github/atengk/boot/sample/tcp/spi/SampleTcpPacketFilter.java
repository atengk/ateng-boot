package io.github.atengk.boot.sample.tcp.spi;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.netty.channel.ChannelHandlerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 车联网出入站报文前置安全与审计过滤器（TcpPacketFilter SPI 示范）
 * 入站阶段自动拦截包含敏感恶意特征串的报文并计数审计，放行合法 JSON 与国标二进制帧；出站阶段仅做旁路审计
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SampleTcpPacketFilter implements TcpPacketFilter {

    /**
     * 约定的模拟阻断特征签名（用于测试及控制台演示入站黑名单载荷过滤）
     */
    public static final String BLOCKED_SIGNATURE = "BLOCKED_SIGNATURE";

    private final SampleTcpGovernanceState governanceState;

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean filterInbound(TcpPacket packet, ChannelHandlerContext ctx) {
        if (packet == null || packet.getPayload() == null || packet.getPayload().isEmpty()) {
            governanceState.recordInboundPassed();
            return true;
        }
        byte[] raw = packet.getPayload().getBytes();
        // 二进制 JT/T 808 帧（首字节 0x7E）免做文本关键字扫描，直接旁路放行
        if (raw.length > 0 && raw[0] == 0x7E) {
            governanceState.recordInboundPassed();
            return true;
        }
        String text = packet.getPayload().asString();
        if (text != null && text.contains(BLOCKED_SIGNATURE)) {
            String remote = ctx != null && ctx.channel() != null ? String.valueOf(ctx.channel().remoteAddress()) : "unknown";
            log.warn("[TCP-FILTER] 命中非法特征签名，拦截入站报文: remote={}, cmd={}", remote, packet.getCommand());
            governanceState.recordInboundBlocked("remote=" + remote + ", signature=" + BLOCKED_SIGNATURE);
            return false;
        }
        governanceState.recordInboundPassed();
        return true;
    }

    @Override
    public TcpPacket filterOutbound(TcpPacket packet, ChannelHandlerContext ctx) {
        governanceState.recordOutboundPassed();
        return packet;
    }
}
