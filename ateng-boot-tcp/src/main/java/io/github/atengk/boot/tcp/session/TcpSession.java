package io.github.atengk.boot.tcp.session;

import io.github.atengk.boot.tcp.enums.CloseReason;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;
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

    public static final AttributeKey<TcpSession> SESSION_KEY = AttributeKey.valueOf("ATENG_TCP_SESSION");

    private final String sessionId;
    private final Channel channel;
    private final SocketAddress remoteAddress;
    private final SocketAddress localAddress;
    private final long connectedAt;

    private volatile String deviceId;
    private volatile long lastActiveTime;
    private volatile CloseReason closeReason;

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
     * 向对端发送数据消息
     *
     * @param message 待发送报文（可以是 TcpPacket、TcpPayload、byte[]、String 等）
     * @return Netty 异步 ChannelFuture
     */
    public ChannelFuture send(Object message) {
        if (!isActive()) {
            throw new IllegalStateException("TCP 会话已断开，无法发送消息: sessionId=" + sessionId);
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

    @Override
    public String toString() {
        return "TcpSession[sessionId=" + sessionId + ", deviceId=" + deviceId + ", remote=" + remoteAddress + ", active=" + isActive() + "]";
    }
}
