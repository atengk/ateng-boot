package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.event.TcpSessionIdleEvent;
import io.github.atengk.boot.tcp.metric.TcpMetricsCollector;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.route.TcpDispatcher;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
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
public class TcpServerChannelHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(TcpServerChannelHandler.class);

    private final TcpServerProperties properties;
    private final TcpSessionManager sessionManager;
    private final TcpRequestCorrelationManager correlationManager;
    private final TcpDispatcher dispatcher;
    private final ApplicationEventPublisher eventPublisher;
    private final TcpMetricsCollector metricsCollector;

    public TcpServerChannelHandler(TcpServerProperties properties,
                                   TcpSessionManager sessionManager,
                                   ApplicationEventPublisher eventPublisher) {
        this(properties, sessionManager, null, null, eventPublisher, null);
    }

    public TcpServerChannelHandler(TcpServerProperties properties,
                                   TcpSessionManager sessionManager,
                                   TcpRequestCorrelationManager correlationManager,
                                   ApplicationEventPublisher eventPublisher) {
        this(properties, sessionManager, correlationManager, null, eventPublisher, null);
    }

    public TcpServerChannelHandler(TcpServerProperties properties,
                                   TcpSessionManager sessionManager,
                                   TcpRequestCorrelationManager correlationManager,
                                   TcpDispatcher dispatcher,
                                   ApplicationEventPublisher eventPublisher) {
        this(properties, sessionManager, correlationManager, dispatcher, eventPublisher, null);
    }

    public TcpServerChannelHandler(TcpServerProperties properties,
                                   TcpSessionManager sessionManager,
                                   TcpRequestCorrelationManager correlationManager,
                                   TcpDispatcher dispatcher,
                                   ApplicationEventPublisher eventPublisher,
                                   TcpMetricsCollector metricsCollector) {
        this.properties = properties;
        this.sessionManager = sessionManager;
        this.correlationManager = correlationManager;
        this.dispatcher = dispatcher;
        this.eventPublisher = eventPublisher;
        this.metricsCollector = metricsCollector;
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
        if (metricsCollector != null) {
            metricsCollector.recordConnection();
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        TcpSession session = ctx.channel().attr(TcpSession.SESSION_KEY).get();
        if (session != null) {
            session.touch();
        }

        if (metricsCollector != null) {
            metricsCollector.recordMessageReceived(resolveByteLength(msg));
        }

        // 全双工 RPC 响应优先拦截唤醒
        if (correlationManager != null && correlationManager.hasPendingRequests()) {
            if (correlationManager.tryComplete(msg)) {
                return;
            }
        }

        // 业务命令字路由分发消费
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
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (metricsCollector != null) {
            metricsCollector.recordMessageSent(resolveByteLength(msg));
        }
        super.write(ctx, msg, promise);
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
        log.error("TCP 连接通道出现网络异常: remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    private TcpPacket toTcpPacket(Object msg) {
        if (msg instanceof TcpPacket p) {
            return p;
        }
        if (msg instanceof io.github.atengk.boot.tcp.model.TcpPayload payload) {
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

    private long resolveByteLength(Object msg) {
        if (msg == null) {
            return 0;
        }
        if (msg instanceof TcpPayload payload) {
            return payload.getBytes().length;
        }
        if (msg instanceof TcpPacket packet && packet.getPayload() != null) {
            return packet.getPayload().getBytes().length;
        }
        if (msg instanceof byte[] bytes) {
            return bytes.length;
        }
        if (msg instanceof String str) {
            return str.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return 0;
    }
}
