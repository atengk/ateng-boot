package io.github.atengk.boot.tcp.codec;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TCP 报文过滤器流水线处理器
 * <p>
 * 负责顺序执行已注册的 {@link TcpPacketFilter} SPI 过滤器，实现入站清洗校验拦截与出站加工包装。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@ChannelHandler.Sharable
public class TcpPacketFilterHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(TcpPacketFilterHandler.class);

    private final List<TcpPacketFilter> filters;

    public TcpPacketFilterHandler(List<TcpPacketFilter> filters) {
        if (filters != null && !filters.isEmpty()) {
            List<TcpPacketFilter> sorted = new ArrayList<>(filters);
            AnnotationAwareOrderComparator.sort(sorted);
            this.filters = Collections.unmodifiableList(sorted);
        } else {
            this.filters = Collections.emptyList();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        TcpPacket packet;
        if (msg instanceof TcpPacket) {
            packet = (TcpPacket) msg;
        } else if (msg instanceof TcpPayload) {
            packet = TcpPacket.of((TcpPayload) msg);
        } else {
            ctx.fireChannelRead(msg);
            return;
        }

        for (TcpPacketFilter filter : filters) {
            try {
                if (!filter.filterInbound(packet, ctx)) {
                    log.debug("入站报文已被过滤器 [{}] 拦截丢弃: {}", filter.getClass().getSimpleName(), packet);
                    return;
                }
            } catch (Exception ex) {
                log.error("入站过滤器 [{}] 执行异常，阻断当前报文", filter.getClass().getSimpleName(), ex);
                return;
            }
        }

        ctx.fireChannelRead(packet);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof TcpPacket) {
            TcpPacket packet = (TcpPacket) msg;
            for (TcpPacketFilter filter : filters) {
                try {
                    packet = filter.filterOutbound(packet, ctx);
                    if (packet == null) {
                        log.debug("出站报文已被过滤器 [{}] 终止发送", filter.getClass().getSimpleName());
                        promise.setSuccess();
                        return;
                    }
                } catch (Exception ex) {
                    log.error("出站过滤器 [{}] 执行异常", filter.getClass().getSimpleName(), ex);
                    promise.setFailure(ex);
                    return;
                }
            }
            ctx.write(packet, promise);
        } else {
            ctx.write(msg, promise);
        }
    }

    public List<TcpPacketFilter> getFilters() {
        return filters;
    }
}
