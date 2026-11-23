package io.github.atengk.boot.tcp.properties;

import io.github.atengk.boot.tcp.enums.BackpressurePolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * TCP 服务端配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
public class TcpServerProperties {

    /**
     * 是否启用 TCP 服务端监听（默认 false）
     */
    private boolean enabled = false;

    /**
     * 监听主机地址（默认 0.0.0.0）
     */
    private String host = "0.0.0.0";

    /**
     * 监听端口（默认 8088）
     */
    private int port = 8088;

    /**
     * Netty Boss 线程池线程数（负责接收客户端连接，默认 1）
     */
    private int bossThreads = 1;

    /**
     * Netty Worker 线程池线程数（负责 I/O 读写与分帧，默认 0 表示 Netty 默认 CPU 核心数 * 2）
     */
    private int workerThreads = 0;

    /**
     * 最大允许同时在线的活跃并发连接数（超出在握手阶段快速拒绝关闭通道，防 FD 耗尽，默认 10000）
     */
    private int maxConnections = 10000;

    /**
     * 优雅停机排空等待超时时间（默认 5s）
     */
    private Duration drainTimeout = Duration.ofSeconds(5);

    /**
     * TCP SO_BACKLOG 队列长度（默认 1024）
     */
    private int soBacklog = 1024;

    /**
     * 是否开启 TCP_NODELAY 禁用 Nagle 算法（默认 true，降低通信延迟）
     */
    private boolean tcpNodelay = true;

    /**
     * 是否开启 SO_KEEPALIVE 底层保活（默认 true）
     */
    private boolean soKeepalive = true;

    /**
     * Netty 写缓冲区低水位阈值（低于该阈值恢复可写，默认 32KB）
     */
    private DataSize writeBufferLowWaterMark = DataSize.ofKilobytes(32);

    /**
     * Netty 写缓冲区高水位阈值（高于该阈值触发不可写背压，默认 64KB）
     */
    private DataSize writeBufferHighWaterMark = DataSize.ofKilobytes(64);

    /**
     * 出站背压拦截策略（默认 FAIL_FAST 快速失败）
     */
    private BackpressurePolicy backpressurePolicy = BackpressurePolicy.FAIL_FAST;

    /**
     * 当背压策略为 BLOCK_TIMEOUT 时的等待阻塞超时阈值（默认 3s）
     */
    private Duration backpressureTimeout = Duration.ofSeconds(3);

    /**
     * 数据帧粘包拆包切片配置
     */
    @NestedConfigurationProperty
    private TcpFramingProperties framing = new TcpFramingProperties();

    /**
     * 空闲心跳检测配置
     */
    @NestedConfigurationProperty
    private TcpIdleProperties idle = new TcpIdleProperties();

    /**
     * TLS/mTLS 加密与证书配置
     */
    @NestedConfigurationProperty
    private TcpSslProperties ssl = new TcpSslProperties();

    /**
     * 网络层 IP 访问控制防火墙配置
     */
    @NestedConfigurationProperty
    private TcpIpFilterProperties ipFilter = new TcpIpFilterProperties();

    /**
     * 网络层流量整形与带宽限速配置
     */
    @NestedConfigurationProperty
    private TcpTrafficShapingProperties trafficShaping = new TcpTrafficShapingProperties();
}
