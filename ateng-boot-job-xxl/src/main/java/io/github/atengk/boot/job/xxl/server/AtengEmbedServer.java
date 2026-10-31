package io.github.atengk.boot.job.xxl.server;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.openapi.executor.ExecutorBiz;
import com.xxl.job.core.openapi.executor.impl.ExecutorBizImpl;
import com.xxl.job.core.server.EmbedServer.EmbedHttpServerHandler;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.security.XxlJobFirewallInboundHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * XXL-Job 生产加固型内嵌 Netty 通信服务端
 *
 * <p>全面强化 XXL-Job 官方 EmbedServer，将 Netty 入站安全防火墙 (CIDR 白名单与 AccessToken 快速校验)、
 * 端口自愈、容器 IP 嗅探与三阶平滑停机排空能力无缝织入通信链路。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class AtengEmbedServer {

    private static final Logger log = LoggerFactory.getLogger(AtengEmbedServer.class);

    private final ExecutorBiz executorBiz = new ExecutorBizImpl();

    private Thread serverThread;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ThreadPoolExecutor bizThreadPool;

    /**
     * 启动加固型 Netty 服务端
     *
     * @param executor           执行器实例
     * @param properties         配置属性
     * @param isDrainingSupplier 平滑排空状态探测
     */
    public void start(XxlJobExecutor executor, XxlJobProperties properties, BooleanSupplier isDrainingSupplier) {
        this.bizThreadPool = new ThreadPoolExecutor(
                0,
                200,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "xxl-job, AtengEmbedServer bizThreadPool-" + count.getAndIncrement());
                    }
                },
                (r, exec) -> {
                    throw new RuntimeException("xxl-job, AtengEmbedServer bizThreadPool is EXHAUSTED!");
                });

        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> startError = new java.util.concurrent.atomic.AtomicReference<>();

        this.serverThread = new Thread(() -> {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            try {
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline()
                                        .addLast(new IdleStateHandler(0, 0, 90, TimeUnit.SECONDS))
                                        .addLast(new HttpServerCodec())
                                        .addLast(new HttpObjectAggregator(5 * 1024 * 1024))
                                        .addLast(new XxlJobFirewallInboundHandler(
                                                properties != null ? properties.getCidrWhitelist() : null,
                                                properties != null ? properties.getAccessToken() : executor.getAccessToken(),
                                                isDrainingSupplier
                                        ))
                                        .addLast(new EmbedHttpServerHandler(executorBiz, executor, bizThreadPool));
                            }
                        })
                        .childOption(ChannelOption.SO_KEEPALIVE, true);

                int port = executor.getPort();
                ChannelFuture future = bootstrap.bind(port).sync();
                log.info(">>>>>>>>>>> ateng-boot xxl-job remoting server start success, nettype = {}, port = {}",
                        AtengEmbedServer.class.getName(), port);

                startLatch.countDown();
                future.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                log.info(">>>>>>>>>>> ateng-boot xxl-job remoting server stop.");
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                startError.set(e);
                startLatch.countDown();
                log.error(">>>>>>>>>>> ateng-boot xxl-job remoting server error.", e);
            } finally {
                stopGroups();
            }
        });

        this.serverThread.setDaemon(true);
        this.serverThread.setName("xxl-job, AtengEmbedServer");
        this.serverThread.start();

        try {
            boolean success = startLatch.await(5, TimeUnit.SECONDS);
            if (!success) {
                throw new IllegalStateException("XXL-Job Netty 服务端启动超时");
            }
            if (startError.get() != null) {
                throw new IllegalStateException("XXL-Job Netty 服务端启动失败", startError.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("XXL-Job Netty 服务端启动等待被中断", e);
        }
    }

    /**
     * 安全停止 Netty 通信服务与业务线程池
     */
    public void stop() {
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
        }

        stopGroups();

        if (bizThreadPool != null) {
            bizThreadPool.shutdown();
        }

        log.info(">>>>>>>>>>> ateng-boot xxl-job remoting server destroy success.");
    }

    private void stopGroups() {
        try {
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
        } catch (Throwable e) {
            log.error("关闭 Netty EventLoopGroup 发生异常", e);
        }
    }

}
