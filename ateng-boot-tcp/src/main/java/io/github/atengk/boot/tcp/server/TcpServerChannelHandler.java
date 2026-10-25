package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.event.TcpSessionIdleEvent;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * TCP 服务端网络通道生命周期与空闲管理处理器
 *
 * @author Ateng
 * @since 2026-10-26
 */
@ChannelHandler.Sharable
public class TcpServerChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(TcpServerChannelHandler.class);

    private final TcpServerProperties properties;
    private final TcpSessionManager sessionManager;
    private final ApplicationEventPublisher eventPublisher;

    public TcpServerChannelHandler(TcpServerProperties properties,
                                  TcpSessionManager sessionManager,
                                  ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.sessionManager = sessionManager;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        int maxConnections = properties.getMaxConnections();
        if (maxConnections > 0 && sessionManager.getActiveCount() >= maxConnections) {
            log.warn("当前服务端并发连接数已达到上限 [{}], 触发过载守卫，主动拒绝新接入连接: remote={}",
                    maxConnections, ctx.channel().remoteAddress());
            ctx.close();
            return;
        }

        String sessionId = IdUtil.fastSimpleUUID();
        TcpSession session = new TcpSession(sessionId, ctx.channel());
        ctx.channel().attr(TcpSession.SESSION_KEY).set(session);

        sessionManager.register(session);
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        TcpSession session = ctx.channel().attr(TcpSession.SESSION_KEY).get();
        if (session != null) {
            session.touch();
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        TcpSession session = ctx.channel().attr(TcpSession.SESSION_KEY).get();
        if (session != null) {
            CloseReason reason = session.getCloseReason() != null ? session.getCloseReason() : CloseReason.NORMAL;
            sessionManager.unregister(session.getSessionId(), reason);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent idleEvent = (IdleStateEvent) evt;
            TcpSession session = ctx.channel().attr(TcpSession.SESSION_KEY).get();
            if (session != null) {
                if (eventPublisher != null) {
                    eventPublisher.publishEvent(new TcpSessionIdleEvent(this, session, idleEvent.state()));
                }
                if (properties.getIdle().isAutoClose()) {
                    log.warn("TCP 连接通道检测到空闲超时 [{}], 触发自动断链保活策略: sessionId={}, remote={}",
                            idleEvent.state(), session.getSessionId(), session.getRemoteAddress());
                    session.close(CloseReason.IDLE_TIMEOUT);
                }
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        TcpSession session = ctx.channel().attr(TcpSession.SESSION_KEY).get();
        if (session != null) {
            session.setCloseReason(CloseReason.ERROR);
        }
        log.warn("TCP 网络连接发生异常，即将断开连接: remote={}, error={}",
                ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }
}
