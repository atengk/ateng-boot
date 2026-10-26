package io.github.atengk.boot.tcp.client;

import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.tcp.codec.TcpFramingDecoderFactory;
import io.github.atengk.boot.tcp.codec.TcpPacketFilterHandler;
import io.github.atengk.boot.tcp.codec.TcpPayloadCodecHandler;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.route.TcpDispatcher;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.github.atengk.boot.tcp.spi.TcpChannelCustomizer;
import io.github.atengk.boot.tcp.spi.TcpPacketFilter;
import io.github.atengk.boot.tcp.ssl.TcpSslContextFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty TCP 客户端连接通道与生命周期管理引擎
 * <p>
 * 支持 {@link SmartLifecycle} 托管、指数退避断线自愈重连以及通道状态管理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TcpClient.class);

    private final String name;
    private final TcpClientProperties properties;
    private final List<TcpPacketFilter> packetFilters;
    private final List<TcpChannelCustomizer> channelCustomizers;
    private final ApplicationEventPublisher eventPublisher;
    private final TcpRequestCorrelationManager correlationManager;
    private final TcpDispatcher dispatcher;

    private EventLoopGroup workerGroup;
    private Bootstrap bootstrap;
    private Channel channel;
    private SslContext sslContext;
    private volatile TcpSession session;
    private volatile boolean running = false;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    public TcpClient(TcpClientProperties properties,
                     List<TcpPacketFilter> packetFilters,
                     List<TcpChannelCustomizer> channelCustomizers,
                     ApplicationEventPublisher eventPublisher,
                     TcpRequestCorrelationManager correlationManager) {
        this("default", properties, packetFilters, channelCustomizers, eventPublisher, correlationManager, null);
    }

    public TcpClient(TcpClientProperties properties,
                     List<TcpPacketFilter> packetFilters,
                     List<TcpChannelCustomizer> channelCustomizers,
                     ApplicationEventPublisher eventPublisher,
                     TcpRequestCorrelationManager correlationManager,
                     TcpDispatcher dispatcher) {
        this("default", properties, packetFilters, channelCustomizers, eventPublisher, correlationManager, dispatcher);
    }

    public TcpClient(String name, TcpClientProperties properties) {
        this(name, properties, Collections.emptyList(), Collections.emptyList(), null, null, null);
    }

    public TcpClient(String name,
                     TcpClientProperties properties,
                     List<TcpPacketFilter> packetFilters,
                     List<TcpChannelCustomizer> channelCustomizers,
                     ApplicationEventPublisher eventPublisher,
                     TcpRequestCorrelationManager correlationManager,
                     TcpDispatcher dispatcher) {
        this.name = StringUtils.hasText(name) ? name : "default";
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.correlationManager = correlationManager;
        this.dispatcher = dispatcher;

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

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        workerGroup = new NioEventLoopGroup();
        bootstrap = new Bootstrap();

        this.sslContext = TcpSslContextFactory.buildClientSslContext(properties.getSsl());
        TcpPacketFilterHandler filterHandler = !packetFilters.isEmpty() ? new TcpPacketFilterHandler(packetFilters) : null;
        TcpClientChannelHandler clientHandler = new TcpClientChannelHandler(this, properties, correlationManager, dispatcher, eventPublisher);

        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, properties.isTcpNodelay())
                .option(ChannelOption.SO_KEEPALIVE, properties.isSoKeepalive())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeout().toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // 0. TLS/mTLS 链路加密
                        if (sslContext != null) {
                            pipeline.addLast("sslHandler", sslContext.newHandler(ch.alloc(), properties.getHost(), properties.getPort()));
                        }

                        // 1. 心跳探测
                        if (properties.getHeartbeat().isEnabled()) {
                            int interval = (int) properties.getHeartbeat().getInterval().toSeconds();
                            pipeline.addLast("idleStateHandler", new IdleStateHandler(0, interval, 0));
                        }

                        // 2. 粘包拆包解码器与对称出站定界符属性绑定
                        if (properties.getFraming().getMode() == io.github.atengk.boot.tcp.enums.FramingMode.DELIMITER) {
                            byte[] delim = TcpFramingDecoderFactory.resolveDelimiterBytes(properties.getFraming());
                            ch.attr(TcpPayloadCodecHandler.DELIMITER_KEY).set(delim);
                        }
                        ChannelHandler frameDecoder = TcpFramingDecoderFactory.createFrameDecoder(properties.getFraming());
                        if (frameDecoder != null) {
                            pipeline.addLast("frameDecoder", frameDecoder);
                        }

                        // 3. 载荷转译与内存回收
                        pipeline.addLast("payloadCodec", TcpPayloadCodecHandler.INSTANCE);

                        // 4. 报文过滤器链
                        if (filterHandler != null) {
                            pipeline.addLast("packetFilter", filterHandler);
                        }

                        // 5. 客户端通道生命周期与 RPC 关联处理器
                        pipeline.addLast("clientHandler", clientHandler);

                        // 6. 通道定制逃生舱
                        for (TcpChannelCustomizer customizer : channelCustomizers) {
                            customizer.customize(ch, "CLIENT", name);
                        }
                    }
                });

        this.running = true;

        try {
            doConnectSync();
            log.info("ateng-boot-tcp 客户端 [{}] 成功启动并连接至服务端: host={}, port={}", this.name, properties.getHost(), properties.getPort());
        } catch (Exception ex) {
            if (properties.getReconnect().isEnabled()) {
                log.warn("ateng-boot-tcp 客户端 [{}] 初次连接未就绪 (host={}, port={})，已自动进入后台指数退避自愈守护: {}",
                        this.name, properties.getHost(), properties.getPort(), ex.getMessage());
                scheduleReconnect();
            } else {
                stop();
                throw new IllegalStateException("启动 TCP 客户端连接失败: host=" + properties.getHost() + ", port=" + properties.getPort(), ex);
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        this.running = false;
        try {
            if (channel != null && channel.isOpen()) {
                channel.close().syncUninterruptibly();
            }
            if (correlationManager != null) {
                correlationManager.close();
            }
        } finally {
            if (workerGroup != null) {
                workerGroup.shutdownGracefully(100, 2000, TimeUnit.MILLISECONDS);
            }
            log.info("ateng-boot-tcp 客户端已平滑关闭");
        }
    }

    /**
     * 同步建立 TCP 连接
     *
     * @throws Exception 连接异常
     */
    public void doConnectSync() throws Exception {
        ChannelFuture future = bootstrap.connect(properties.getHost(), properties.getPort()).sync();
        this.channel = future.channel();
        this.reconnectAttempts.set(0);
    }

    /**
     * 基于指数退避调度后台重连任务
     */
    public void scheduleReconnect() {
        if (!running || !properties.getReconnect().isEnabled()) {
            return;
        }
        if (isConnected()) {
            return;
        }
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }

        int attempt = reconnectAttempts.incrementAndGet();
        int maxAttempts = properties.getReconnect().getMaxAttempts();
        if (maxAttempts > 0 && attempt > maxAttempts) {
            log.warn("TCP 客户端自动重连已耗尽全部 {} 次尝试，终止重试", maxAttempts);
            reconnecting.set(false);
            return;
        }

        long initialDelayMs = properties.getReconnect().getInitialInterval().toMillis();
        long maxDelayMs = properties.getReconnect().getMaxInterval().toMillis();
        double multiplier = properties.getReconnect().getMultiplier();

        long delayMs = (long) (initialDelayMs * Math.pow(multiplier, Math.max(0, attempt - 1)));
        delayMs = Math.min(delayMs, maxDelayMs);

        log.warn("TCP 客户端将在 {}ms 后尝试第 {} 次自愈重连: host={}, port={}...",
                delayMs, attempt, properties.getHost(), properties.getPort());

        workerGroup.schedule(() -> {
            try {
                if (!running || isConnected()) {
                    reconnecting.set(false);
                    return;
                }
                ChannelFuture future = bootstrap.connect(properties.getHost(), properties.getPort());
                future.addListener((ChannelFutureListener) f -> {
                    reconnecting.set(false);
                    if (f.isSuccess()) {
                        this.channel = f.channel();
                        this.reconnectAttempts.set(0);
                        log.info("TCP 客户端第 {} 次自愈重连成功: host={}, port={}", attempt, properties.getHost(), properties.getPort());
                    } else {
                        log.warn("TCP 客户端第 {} 次重连失败: {}", attempt, f.cause().getMessage());
                        scheduleReconnect();
                    }
                });
            } catch (Exception ex) {
                reconnecting.set(false);
                log.warn("TCP 客户端重连调度发生异常: {}", ex.getMessage());
                scheduleReconnect();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 手动触发同步重连（依托 Core 模块 RetryUtil 进行有界重试防卫）
     */
    public synchronized void reconnect() {
        if (channel != null && channel.isOpen()) {
            channel.close().syncUninterruptibly();
        }
        reconnectAttempts.set(0);
        int maxAttempts = properties.getReconnect().getMaxAttempts() > 0 ? properties.getReconnect().getMaxAttempts() : 3;
        RetryUtil.RetryConfig retryConfig = RetryUtil.RetryConfig.builder()
                .maxAttempts(maxAttempts)
                .delay(properties.getReconnect().getInitialInterval())
                .multiplier(properties.getReconnect().getMultiplier())
                .maxDelay(properties.getReconnect().getMaxInterval())
                .build();

        RetryUtil.execute(() -> {
            try {
                doConnectSync();
            } catch (Exception e) {
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("TCP 重连失败: " + e.getMessage(), e);
            }
        }, retryConfig);
    }

    void onConnected(Channel ch, TcpSession sess) {
        this.channel = ch;
        this.session = sess;
        this.reconnectAttempts.set(0);
        this.reconnecting.set(false);
    }

    void onDisconnected(Channel ch) {
        if (this.channel == ch) {
            this.channel = null;
            this.session = null;
        }
        if (running && properties.getReconnect().isEnabled()) {
            scheduleReconnect();
        }
    }

    /**
     * 当前客户端是否已建立活跃物理长连接
     *
     * @return true 表示已连接
     */
    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    /**
     * 获取底层 Netty 通道实例
     *
     * @return 通道实例
     */
    public Channel getChannel() {
        return channel;
    }

    public String getName() {
        return name;
    }

    /**
     * 获取客户端领域会话实体
     *
     * @return 会话实体
     */
    public TcpSession getSession() {
        return session;
    }

    /**
     * 主动断开当前长连接通道
     */
    public void disconnect() {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    public TcpClientProperties getProperties() {
        return properties;
    }

    public TcpRequestCorrelationManager getCorrelationManager() {
        return correlationManager;
    }

    public TcpDispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
