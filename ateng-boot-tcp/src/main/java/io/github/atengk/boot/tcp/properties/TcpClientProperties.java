package io.github.atengk.boot.tcp.properties;

import io.github.atengk.boot.tcp.enums.BackpressurePolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * TCP 客户端配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
public class TcpClientProperties {

    /**
     * 是否启用 TCP 客户端主动长连接（默认 false）
     */
    private boolean enabled = false;

    /**
     * 连接目标服务端主机地址（默认 127.0.0.1）
     */
    private String host = "127.0.0.1";

    /**
     * 连接目标服务端端口（默认 8088）
     */
    private int port = 8088;

    /**
     * 连接超时时长（默认 5s）
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * 全双工请求-响应 RPC 默认调用超时时长（默认 10s）
     */
    private Duration requestTimeout = Duration.ofSeconds(10);

    /**
     * 是否开启 TCP_NODELAY 禁用 Nagle 算法（默认 true）
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
     * 客户端主动心跳配置
     */
    @NestedConfigurationProperty
    private TcpHeartbeatProperties heartbeat = new TcpHeartbeatProperties();

    /**
     * 断线指数退避自愈重连配置
     */
    @NestedConfigurationProperty
    private TcpReconnectProperties reconnect = new TcpReconnectProperties();

    /**
     * TLS/SSL 加密与双向认证配置
     */
    @NestedConfigurationProperty
    private TcpSslProperties ssl = new TcpSslProperties();
}
