package io.github.atengk.boot.tcp.server;

import io.github.atengk.boot.tcp.codec.TcpFramingDecoderFactory;
import io.github.atengk.boot.tcp.codec.TcpPacketFilterHandler;
import io.github.atengk.boot.tcp.codec.TcpPayloadCodecHandler;
import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.properties.TcpServerProperties;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.spi.TcpChannelCustomizer;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Netty TCP 服务端生命周期管理引擎
 * <p>
 * 负责 Netty Boss/Worker 线程池初始化、端口绑定、连接流水线装配与平滑停机排空。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);

    private final TcpServerProperties properties;
    private final TcpSessionManager sessionManager;
    private final List<TcpPacketFilter> packetFilters;
    private final List<TcpChannelCustomizer> channelCustomizers;
    private final ApplicationEventPublisher eventPublisher;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;
    private int actualPort;

    public TcpServer(TcpServerProperties properties,
                     TcpSessionManager sessionManager,
                     List<TcpPacketFilter> packetFilters,
                     List<TcpChannelCustomizer> channelCustomizers,
                     ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.sessionManager = sessionManager;
        this.eventPublisher = eventPublisher;

        if (packetFilters != null && !packetFilters.isEmpty()) {
            List<TcpPacketFilter> sorted = new ArrayList<>(packetFilters);
            AnnotationAwareOrderComparator.sort(sorted);
            this.packetFilters = Collections.unmodifiableList(sorted);
        } else {
            this.packetFilters = Collections.emptyList();
        }

        if (channelCustomizers != null && !channelCustomizers.isEmpty()) {
            List<TcpChannelCustomizer> sorted = new ArrayList<>(channelCustomizers);
            AnnotationAwareOrderComparator.sort(sorted);
            this.channelCustomizers = Collections.unmodifiableList(sorted);
        } else {
            this.channelCustomizers = Collections.emptyList();
        }
    }

    /**
     * 启动 TCP 服务端并绑定监听端口
     */
    public synchronized void start() {
        if (running) {
            return;
        }

        bossGroup = new NioEventLoopGroup(properties.getBossThreads());
        workerGroup = properties.getWorkerThreads() > 0
                ? new NioEventLoopGroup(properties.getWorkerThreads())
                : new NioEventLoopGroup();

        TcpServerChannelHandler serverHandler = new TcpServerChannelHandler(properties, sessionManager, eventPublisher);
        TcpPacketFilterHandler filterHandler = !packetFilters.isEmpty() ? new TcpPacketFilterHandler(packetFilters) : null;

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, properties.getSoBacklog())
                    .childOption(ChannelOption.TCP_NODELAY, properties.isTcpNodelay())
                    .childOption(ChannelOption.SO_KEEPALIVE, properties.isSoKeepalive())
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();

                            // 1. 空闲心跳检测处理器
                            Duration r = properties.getIdle().getReaderIdleTime();
                            Duration w = properties.getIdle().getWriterIdleTime();
                            Duration a = properties.getIdle().getAllIdleTime();
                            if (!r.isZero() || !w.isZero() || !a.isZero()) {
                                pipeline.addLast("idleStateHandler",
                                        new IdleStateHandler((int) r.toSeconds(), (int) w.toSeconds(), (int) a.toSeconds()));
                            }

                            // 2. 粘包拆包解码器
                            ChannelHandler frameDecoder = TcpFramingDecoderFactory.createFrameDecoder(properties.getFraming());
                            if (frameDecoder != null) {
                                pipeline.addLast("frameDecoder", frameDecoder);
                            }

                            // 3. 安全堆内存转译与直接内存释放闭环
                            pipeline.addLast("payloadCodec", TcpPayloadCodecHandler.INSTANCE);

                            // 4. 报文过滤器链
                            if (filterHandler != null) {
                                pipeline.addLast("packetFilter", filterHandler);
                            }

                            // 5. 服务端连接通道生命周期处理器
                            pipeline.addLast("serverHandler", serverHandler);

                            // 6. 执行通道定制逃生舱
                            for (TcpChannelCustomizer customizer : channelCustomizers) {
                                customizer.customize(ch);
                            }
                        }
                    });

            ChannelFuture future = bootstrap.bind(properties.getHost(), properties.getPort()).sync();
            serverChannel = future.channel();

            if (serverChannel.localAddress() instanceof InetSocketAddress) {
                this.actualPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            } else {
                this.actualPort = properties.getPort();
            }

            this.running = true;
            log.info("ateng-boot-tcp 服务端成功启动并监听: host={}, port={}, actualPort={}",
                    properties.getHost(), properties.getPort(), this.actualPort);

        } catch (Exception ex) {
            stop();
            throw new IllegalStateException("启动 TCP 服务端失败: host=" + properties.getHost() + ", port=" + properties.getPort(), ex);
        }
    }

    /**
     * 平滑停止 TCP 服务端并注销全部会话
     */
    public synchronized void stop() {
        if (!running && serverChannel == null && bossGroup == null && workerGroup == null) {
            return;
        }

        log.info("正在平滑关闭 TCP 服务端: port={}...", actualPort);

        // 1. 立即停止接收新连接
        if (serverChannel != null) {
            try {
                serverChannel.close().syncUninterruptibly();
            } catch (Exception ex) {
                log.warn("关闭服务端 ServerSocket 异常", ex);
            }
            serverChannel = null;
        }

        // 2. 优雅注销所有存量连接
        try {
            sessionManager.closeAllSessions(CloseReason.SERVER_SHUTDOWN);
        } catch (Exception ex) {
            log.warn("注销存量客户端会话异常", ex);
        }

        // 3. 释放 Netty 线程池
        long drainMillis = properties.getDrainTimeout() != null ? properties.getDrainTimeout().toMillis() : 5000L;
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(100, drainMillis, TimeUnit.MILLISECONDS).syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(100, drainMillis, TimeUnit.MILLISECONDS).syncUninterruptibly();
            bossGroup = null;
        }

        this.running = false;
        log.info("TCP 服务端已安全停止");
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public int getPhase() {
        return 0;
    }

    public int getActualPort() {
        return actualPort;
    }

    public TcpServerProperties getProperties() {
        return properties;
    }

    public TcpSessionManager getSessionManager() {
        return sessionManager;
    }
}
