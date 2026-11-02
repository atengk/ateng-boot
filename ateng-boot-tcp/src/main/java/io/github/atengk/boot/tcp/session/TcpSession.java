package io.github.atengk.boot.tcp.session;

import io.github.atengk.boot.tcp.enums.BackpressurePolicy;
import io.github.atengk.boot.tcp.enums.CloseReason;
import io.github.atengk.boot.tcp.exception.TcpBackpressureException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 统一标准 TCP 领域会话契约
 * <p>
 * 封装底层 Netty Channel、唯一会话标识（sessionId）、设备业务标识（deviceId）、
 * 连接创建与最后活跃时间戳及线程安全的会话属性上下文。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpSession {

    private static final Logger log = LoggerFactory.getLogger(TcpSession.class);

    public static final AttributeKey<TcpSession> SESSION_KEY = AttributeKey.valueOf("ATENG_TCP_SESSION");

    private final String sessionId;
    private final Channel channel;
    private final SocketAddress remoteAddress;
    private final SocketAddress localAddress;
    private final long connectedAt;

    private volatile String deviceId;
    private volatile long lastActiveTime;
    private volatile CloseReason closeReason;
    private volatile BackpressurePolicy backpressurePolicy = BackpressurePolicy.FAIL_FAST;
    private volatile Duration backpressureTimeout = Duration.ofSeconds(3);

    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();

    public TcpSession(String sessionId, Channel channel) {
        this.sessionId = sessionId;
        this.channel = channel;
        this.remoteAddress = channel != null ? channel.remoteAddress() : null;
        this.localAddress = channel != null ? channel.localAddress() : null;
        this.connectedAt = System.currentTimeMillis();
        this.lastActiveTime = this.connectedAt;
    }

    /**
     * 判断当前会话网络通道是否仍处于活跃连接状态
     *
     * @return true 活跃
     */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    /**
     * 判断底层网络通道当前是否处于可写状态（未达到写高水位）
     *
     * @return true 可写；false 达到高水位背压饱和
     */
    public boolean isWritable() {
        return channel != null && channel.isWritable();
    }

    /**
     * 获取当前通道出站队列中排队等待发送的积压字节数
     *
     * @return 积压待写字节数（不可用时返回 0）
     */
    public long getPendingWriteBytes() {
        if (channel != null) {
            try {
                if (channel.unsafe() != null && channel.unsafe().outboundBuffer() != null) {
                    return channel.unsafe().outboundBuffer().totalPendingWriteBytes();
                }
            } catch (Exception ignored) {
                // 自定义或 Mock 通道不支持 unsafe 时容错保底返回 0
            }
        }
        return 0L;
    }

    /**
     * 向对端发送数据消息（采用当前会话配置的背压策略）
     *
     * @param message 待发送报文（可以是 TcpPacket、TcpPayload、byte[]、String 等）
     * @return Netty 异步 ChannelFuture
     */
    public ChannelFuture send(Object message) {
        return send(message, this.backpressurePolicy, this.backpressureTimeout);
    }

    /**
     * 向对端发送数据消息并显式指定出站背压策略
     *
     * @param message 待发送报文
     * @param policy  背压策略（为 null 时默认采用当前会话策略）
     * @param timeout 超时窗口（为 null 时默认采用当前会话超时）
     * @return Netty 异步 ChannelFuture
     */
    public ChannelFuture send(Object message, BackpressurePolicy policy, Duration timeout) {
        if (!isActive()) {
            throw new IllegalStateException("TCP 会话已断开，无法发送消息: sessionId=" + sessionId);
        }
        BackpressurePolicy effectivePolicy = policy != null ? policy : (this.backpressurePolicy != null ? this.backpressurePolicy : BackpressurePolicy.FAIL_FAST);
        if (!isWritable()) {
            switch (effectivePolicy) {
                case FAIL_FAST -> throw new TcpBackpressureException(
                        "TCP 出站缓冲区已达高水位且通道不可写，触发背压快速失败: sessionId=" + sessionId +
                                ", pendingWriteBytes=" + getPendingWriteBytes());
                case BLOCK_TIMEOUT -> {
                    Duration effectiveTimeout = timeout != null ? timeout : (this.backpressureTimeout != null ? this.backpressureTimeout : Duration.ofSeconds(3));
                    long waitNanos = effectiveTimeout.toNanos();
                    long deadline = System.nanoTime() + waitNanos;
                    while (!isWritable() && isActive()) {
                        long remainingNanos = deadline - System.nanoTime();
                        if (remainingNanos <= 0) {
                            throw new TcpBackpressureException(
                                    "TCP 出站缓冲区背压等待可写超时 (" + effectiveTimeout +
                                            "): sessionId=" + sessionId + ", pendingWriteBytes=" + getPendingWriteBytes());
                        }
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new TcpBackpressureException("TCP 出站缓冲区背压等待被中断: sessionId=" + sessionId, e);
                        }
                    }
                    if (!isActive()) {
                        throw new IllegalStateException("TCP 会话在等待背压期间已断开: sessionId=" + sessionId);
                    }
                }
                case WARN_AND_SEND -> {
                    log.warn("TCP 出站缓冲区已达高水位，根据 WARN_AND_SEND 策略强制推流: sessionId={}, pendingWriteBytes={}",
                            sessionId, getPendingWriteBytes());
                }
            }
        }
        touch();
        return channel.writeAndFlush(message);
    }

    /**
     * 主动关闭当前会话通道并记录关闭原因
     *
     * @param reason 关闭原因枚举
     * @return Netty 异步 ChannelFuture
     */
    public ChannelFuture close(CloseReason reason) {
        this.closeReason = reason;
        if (channel != null && channel.isOpen()) {
            return channel.close();
        }
        return channel != null ? channel.newSucceededFuture() : null;
    }

    /**
     * 刷新最后活跃时间戳
     */
    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    // --- 属性上下文操作 ---

    public void setAttribute(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        } else {
            attributes.remove(key);
        }
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> targetClass) {
        Object val = attributes.get(key);
        if (val != null && targetClass.isInstance(val)) {
            return (T) val;
        }
        return null;
    }

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    public Object removeAttribute(String key) {
        return attributes.remove(key);
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    // --- Getters & Setters ---

    public String getSessionId() {
        return sessionId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Channel getChannel() {
        return channel;
    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    public CloseReason getCloseReason() {
        return closeReason;
    }

    public void setCloseReason(CloseReason closeReason) {
        this.closeReason = closeReason;
    }

    public BackpressurePolicy getBackpressurePolicy() {
        return backpressurePolicy;
    }

    public void setBackpressurePolicy(BackpressurePolicy backpressurePolicy) {
        this.backpressurePolicy = backpressurePolicy;
    }

    public Duration getBackpressureTimeout() {
        return backpressureTimeout;
    }

    public void setBackpressureTimeout(Duration backpressureTimeout) {
        this.backpressureTimeout = backpressureTimeout;
    }

    @Override
    public String toString() {
        return "TcpSession[sessionId=" + sessionId + ", deviceId=" + deviceId + ", remote=" + remoteAddress + ", active=" + isActive() + "]";
    }
}
