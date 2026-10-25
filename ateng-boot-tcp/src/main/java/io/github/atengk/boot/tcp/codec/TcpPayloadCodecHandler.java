package io.github.atengk.boot.tcp.codec;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;

/**
 * TCP 载荷编解码处理器
 * <p>
 * 负责入站 {@link ByteBuf} 到安全堆内存 {@link TcpPayload} 的转译，并在 finally 块中绝对可靠释放底层引用计数；
 * 同时负责出站载荷对象到网络 ByteBuf 的序列化。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@ChannelHandler.Sharable
public class TcpPayloadCodecHandler extends ChannelDuplexHandler {

    public static final TcpPayloadCodecHandler INSTANCE = new TcpPayloadCodecHandler();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf byteBuf = (ByteBuf) msg;
            try {
                byte[] bytes = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(bytes);
                TcpPayload payload = TcpPayload.of(bytes);
                ctx.fireChannelRead(payload);
            } finally {
                ReferenceCountUtil.release(byteBuf);
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof TcpPayload) {
            TcpPayload payload = (TcpPayload) msg;
            ByteBuf buf = ctx.alloc().buffer(payload.length());
            buf.writeBytes(payload.asBytes());
            ctx.write(buf, promise);
        } else if (msg instanceof TcpPacket) {
            TcpPacket packet = (TcpPacket) msg;
            TcpPayload payload = packet.getPayload();
            ByteBuf buf = ctx.alloc().buffer(payload.length());
            buf.writeBytes(payload.asBytes());
            ctx.write(buf, promise);
        } else if (msg instanceof byte[]) {
            byte[] bytes = (byte[]) msg;
            ByteBuf buf = ctx.alloc().buffer(bytes.length);
            buf.writeBytes(bytes);
            ctx.write(buf, promise);
        } else if (msg instanceof String) {
            byte[] bytes = ((String) msg).getBytes(StandardCharsets.UTF_8);
            ByteBuf buf = ctx.alloc().buffer(bytes.length);
            buf.writeBytes(bytes);
            ctx.write(buf, promise);
        } else {
            ctx.write(msg, promise);
        }
    }
}
