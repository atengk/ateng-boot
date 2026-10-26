package io.github.atengk.boot.tcp.codec;

import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
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
    public static final AttributeKey<byte[]> DELIMITER_KEY = AttributeKey.valueOf("ateng.tcp.framing.delimiter");

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
        ByteBuf buf = null;
        if (msg instanceof TcpPayload) {
            TcpPayload payload = (TcpPayload) msg;
            buf = ctx.alloc().buffer(payload.length() + 8);
            buf.writeBytes(payload.asBytes());
        } else if (msg instanceof TcpPacket) {
            TcpPacket packet = (TcpPacket) msg;
            TcpPayload payload = packet.getPayload();
            buf = ctx.alloc().buffer(payload.length() + 8);
            buf.writeBytes(payload.asBytes());
        } else if (msg instanceof byte[]) {
            byte[] bytes = (byte[]) msg;
            buf = ctx.alloc().buffer(bytes.length + 8);
            buf.writeBytes(bytes);
        } else if (msg instanceof String) {
            byte[] bytes = ((String) msg).getBytes(StandardCharsets.UTF_8);
            buf = ctx.alloc().buffer(bytes.length + 8);
            buf.writeBytes(bytes);
        }

        if (buf != null) {
            // 对称性定界符守卫：若通道挂载了定界符分帧且出站末尾未包含定界符，自动补齐
            byte[] delimiter = ctx.channel().attr(DELIMITER_KEY).get();
            if (delimiter != null && delimiter.length > 0 && !endsWith(buf, delimiter)) {
                buf.writeBytes(delimiter);
            }
            ctx.write(buf, promise);
        } else {
            ctx.write(msg, promise);
        }
    }

    private static boolean endsWith(ByteBuf buf, byte[] delimiter) {
        if (buf.readableBytes() < delimiter.length) {
            return false;
        }
        int writerIndex = buf.writerIndex();
        for (int i = 0; i < delimiter.length; i++) {
            if (buf.getByte(writerIndex - delimiter.length + i) != delimiter[i]) {
                return false;
            }
        }
        return true;
    }
}
