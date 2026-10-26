package io.github.atengk.boot.tcp.client;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.event.TcpSessionClosedEvent;
import io.github.atengk.boot.tcp.event.TcpSessionConnectedEvent;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.route.TcpDispatcher;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * TCP 客户端网络通道生命周期与 RPC 拦截处理器
 * <p>
 * 负责客户端连接就绪、断链感知、全双工响应精准拦截与主动心跳保活。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@ChannelHandler.Sharable
public class TcpClientChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(TcpClientChannelHandler.class);

    private final TcpClient client;
    private final TcpClientProperties properties;
    private final TcpRequestCorrelationManager correlationManager;
    private final TcpDispatcher dispatcher;
    private final ApplicationEventPublisher eventPublisher;

    public TcpClientChannelHandler(TcpClient client,
                                   TcpClientProperties properties,
                                   TcpRequestCorrelationManager correlationManager,
                                   ApplicationEventPublisher eventPublisher) {
        this(client, properties, correlationManager, null, eventPublisher);
    }

    public TcpClientChannelHandler(TcpClient client,
                                   TcpClientProperties properties,
                                   TcpRequestCorrelationManager correlationManager,
                                   TcpDispatcher dispatcher,
                                   ApplicationEventPublisher eventPublisher) {
        this.client = client;
        this.properties = properties;
        this.correlationManager = correlationManager;
        this.dispatcher = dispatcher;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String sessionId = IdUtil.fastSimpleUUID();
        TcpSession session = new TcpSession(sessionId, ctx.channel());
        ctx.channel().attr(TcpSession.SESSION_KEY).set(session);

        client.onConnected(ctx.channel(), session);
        log.info("TCP 客户端通道已激活: sessionId={}, remote={}", sessionId, ctx.channel().remoteAddress());

        if (eventPublisher != null) {
            eventPublisher.publishEvent(new TcpSessionConnectedEvent(client, session));
        }

        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        TcpSession session = ctx.channel().attr(TcpSession.SESSION_KEY).get();
        if (session != null) {
            session.touch();
        }

        // 1. 全双工 RPC 关联应答优先拦截唤醒
        if (correlationManager != null && correlationManager.hasPendingRequests()) {
            if (correlationManager.tryComplete(msg)) {
                return;
            }
        }

        // 2. 识别并消费心跳应答包
        if (isHeartbeatResponse(msg)) {
            log.debug("收到 TCP 服务端返回的心跳响应包: {}", msg);
            return;
        }

        // 3. 业务命令字路由分发消费
        if (dispatcher != null && session != null) {
            TcpPacket packet = toTcpPacket(msg);
            if (packet != null) {
                dispatcher.dispatch(session, packet);
                return;
            }
        }

        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        TcpSession session = ctx.channel().attr(TcpSession.SESSION_KEY).get();
        CloseReason reason = session != null && session.getCloseReason() != null
                ? session.getCloseReason()
                : CloseReason.NORMAL;

        client.onDisconnected(ctx.channel());
        log.warn("TCP 客户端连接已断开: remote={}, reason={}", ctx.channel().remoteAddress(), reason);

        if (eventPublisher != null && session != null) {
            eventPublisher.publishEvent(new TcpSessionClosedEvent(client, session, reason));
        }

        super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent idleEvent = (IdleStateEvent) evt;
            if (idleEvent.state() == IdleState.WRITER_IDLE || idleEvent.state() == IdleState.ALL_IDLE) {
                if (properties.getHeartbeat().isEnabled()) {
                    String ping = properties.getHeartbeat().getPayload();
                    log.debug("TCP 客户端触发写空闲心跳，发送探测包: payload={}", ping);
                    ctx.writeAndFlush(ping);
                }
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("TCP 客户端通道捕获未受控异常: remote={}, error={}", ctx.channel().remoteAddress(), cause.getMessage(), cause);
        TcpSession session = ctx.channel().attr(TcpSession.SESSION_KEY).get();
        if (session != null) {
            session.setCloseReason(CloseReason.ERROR);
        }
        ctx.close();
    }

    private boolean isHeartbeatResponse(Object msg) {
        if (!properties.getHeartbeat().isEnabled()) {
            return false;
        }
        if (msg instanceof TcpPayload) {
            String text = ((TcpPayload) msg).asString();
            return "PONG".equalsIgnoreCase(text != null ? text.trim() : "");
        }
        if (msg instanceof String) {
            return "PONG".equalsIgnoreCase(((String) msg).trim());
        }
        return false;
    }

    private TcpPacket toTcpPacket(Object msg) {
        if (msg instanceof TcpPacket p) {
            return p;
        }
        if (msg instanceof TcpPayload payload) {
            return TcpPacket.of(payload);
        }
        if (msg instanceof byte[] bytes) {
            return TcpPacket.ofBytes(bytes);
        }
        if (msg instanceof String str) {
            return TcpPacket.ofText(str);
        }
        return null;
    }
}
