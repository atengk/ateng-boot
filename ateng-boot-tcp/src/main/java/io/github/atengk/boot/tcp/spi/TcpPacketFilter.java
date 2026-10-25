package io.github.atengk.boot.tcp.spi;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.core.Ordered;

/**
 * TCP 通信报文过滤器 SPI 契约
 * <p>
 * 位于数据帧分帧之后、业务分发之前，负责入站清洗校验与出站包装转义。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public interface TcpPacketFilter extends Ordered {

    /**
     * 入站报文前置过滤与合法性校验
     *
     * @param packet 入站解包后的 TcpPacket
     * @param ctx    Netty 通道上下文
     * @return true 表示放行继续向后派发；false 表示拦截丢弃（如校验和错误或非法格式）
     */
    default boolean filterInbound(TcpPacket packet, ChannelHandlerContext ctx) {
        return true;
    }

    /**
     * 出站报文后置加工与转义封装
     *
     * @param packet 即将发送的原始 TcpPacket
     * @param ctx    Netty 通道上下文
     * @return 加工包装后的 TcpPacket（或原样返回）
     */
    default TcpPacket filterOutbound(TcpPacket packet, ChannelHandlerContext ctx) {
        return packet;
    }

    @Override
    default int getOrder() {
        return 0;
    }
}
