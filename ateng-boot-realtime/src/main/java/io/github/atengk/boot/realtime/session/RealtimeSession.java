package io.github.atengk.boot.realtime.session;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.enums.ChannelType;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.ratelimit.SessionRateLimiter;
import io.github.atengk.boot.realtime.sse.SseStreamHelper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时通信受管统一会话实体
 * <p>
 * 抽象并封装底层物理连接（WebSocketSession 或 SseEmitter），
 * 统一承载 SessionKey 多端路由坐标、租户 ID、通道类型、扩展属性与最后活跃时间，
 * 提供对等统一的发送消息与关闭会话门面。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Getter
public class RealtimeSession implements Serializable {

    private final SessionKey sessionKey;
    private final ChannelType channelType;
    private final Long tenantId;
    private final Map<String, Object> attributes;
    private final transient Object rawSession;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile long lastActiveTime;
    @Setter
    private transient volatile SessionRateLimiter rateLimiter;

    /**
     * 受保护构造函数
     *
     * @param sessionKey  会话三维唯一坐标
     * @param channelType 通道协议类型
     * @param rawSession  底层物理连接对象
     * @param tenantId    多租户标识（可空）
     * @param attributes  扩展会话属性映射（可空）
     */
    protected RealtimeSession(SessionKey sessionKey,
                              ChannelType channelType,
                              Object rawSession,
                              Long tenantId,
                              Map<String, Object> attributes) {
        this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey must not be null");
        this.channelType = Objects.requireNonNull(channelType, "channelType must not be null");
        this.rawSession = Objects.requireNonNull(rawSession, "rawSession must not be null");
        this.tenantId = tenantId;
        this.attributes = new ConcurrentHashMap<>();
        if (attributes != null && !attributes.isEmpty()) {
            this.attributes.putAll(attributes);
        }
        this.lastActiveTime = System.currentTimeMillis();
    }

    /**
     * 包装 WebSocket 物理连接为受管会话
     *
     * @param sessionKey 会话坐标
     * @param session    底层 WebSocket 会话
     * @return 受管统一会话
     */
    public static RealtimeSession ofWebSocket(SessionKey sessionKey, WebSocketSession session) {
        return ofWebSocket(sessionKey, session, null, null);
    }

    /**
     * 包装 WebSocket 物理连接为受管会话（完整属性）
     *
     * @param sessionKey 会话坐标
     * @param session    底层 WebSocket 会话
     * @param tenantId   多租户标识
     * @param attributes 扩展属性
     * @return 受管统一会话
     */
    public static RealtimeSession ofWebSocket(SessionKey sessionKey,
                                              WebSocketSession session,
                                              Long tenantId,
                                              Map<String, Object> attributes) {
        return new RealtimeSession(sessionKey, ChannelType.WEBSOCKET, session, tenantId, attributes);
    }

    /**
     * 包装 SSE 物理连接为受管会话
     *
     * @param sessionKey 会话坐标
     * @param emitter    底层 SseEmitter
     * @return 受管统一会话
     */
    public static RealtimeSession ofSse(SessionKey sessionKey, SseEmitter emitter) {
        return ofSse(sessionKey, emitter, null, null);
    }

    /**
     * 包装 SSE 物理连接为受管会话（完整属性）
     *
     * @param sessionKey 会话坐标
     * @param emitter    底层 SseEmitter
     * @param tenantId   多租户标识
     * @param attributes 扩展属性
     * @return 受管统一会话
     */
    public static RealtimeSession ofSse(SessionKey sessionKey,
                                        SseEmitter emitter,
                                        Long tenantId,
                                        Map<String, Object> attributes) {
        return new RealtimeSession(sessionKey, ChannelType.SSE, emitter, tenantId, attributes);
    }

    /**
     * 获取归属用户标识
     */
    public String getUserId() {
        return sessionKey.getUserId();
    }

    /**
     * 获取端点客户端会话标识
     */
    public String getClientId() {
        return sessionKey.getClientId();
    }

    /**
     * 获取设备类型
     */
    public String getDeviceType() {
        return sessionKey.getDeviceType();
    }

    /**
     * 刷新最后活跃时间戳
     */
    public void updateActiveTime() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    /**
     * 判断当前会话是否处于开启活跃状态
     */
    public boolean isOpen() {
        if (closed.get()) {
            return false;
        }
        if (channelType == ChannelType.WEBSOCKET && rawSession instanceof WebSocketSession ws) {
            return ws.isOpen();
        }
        return true;
    }

    /**
     * 统一同步向当前会话下发强类型实时消息
     *
     * @param message 实时业务消息载荷
     * @throws IOException 当底层通道关闭或发送失败时抛出
     */
    public void sendMessage(RealtimeMessage<?> message) throws IOException {
        Objects.requireNonNull(message, "message must not be null");
        if (!isOpen()) {
            throw new IOException("Session is closed: " + sessionKey);
        }
        updateActiveTime();
        if (channelType == ChannelType.WEBSOCKET && rawSession instanceof WebSocketSession ws) {
            String json = JsonUtil.toJsonString(message);
            ws.sendMessage(new TextMessage(json));
        } else if (channelType == ChannelType.SSE && rawSession instanceof SseEmitter emitter) {
            boolean success = SseStreamHelper.sendEvent(emitter, message);
            if (!success) {
                markClosed();
                throw new IOException("Failed to send message via SSE session: " + sessionKey);
            }
        }
    }

    /**
     * 以默认正常状态码关闭会话
     */
    public void close() {
        close(RealtimeCloseStatus.NORMAL);
    }

    /**
     * 以指定状态码优雅关闭会话
     *
     * @param status 关闭状态码
     */
    public void close(CloseStatus status) {
        if (closed.compareAndSet(false, true)) {
            CloseStatus closeStatus = status != null ? status : RealtimeCloseStatus.NORMAL;
            if (channelType == ChannelType.WEBSOCKET && rawSession instanceof WebSocketSession ws) {
                try {
                    if (ws.isOpen()) {
                        ws.close(closeStatus);
                    }
                } catch (Throwable t) {
                    log.debug("[Realtime] 关闭 WebSocket 会话异常: sessionKey={}, error={}", sessionKey, t.getMessage());
                }
            } else if (channelType == ChannelType.SSE && rawSession instanceof SseEmitter emitter) {
                try {
                    emitter.complete();
                } catch (Throwable t) {
                    if (SseStreamHelper.isClientAbortException(t)) {
                        log.debug("[Realtime] SSE 客户端主动断开: sessionKey={}", sessionKey);
                    } else {
                        log.debug("[Realtime] 关闭 SSE 会话异常: sessionKey={}, error={}", sessionKey, t.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 标记会话已关闭
     */
    public void markClosed() {
        closed.set(true);
    }

    /**
     * 读取扩展属性
     */
    public Object getAttribute(String name) {
        return name != null ? attributes.get(name) : null;
    }

    /**
     * 设置扩展属性
     */
    public void setAttribute(String name, Object value) {
        if (name != null) {
            if (value != null) {
                attributes.put(name, value);
            } else {
                attributes.remove(name);
            }
        }
    }

    /**
     * 移除扩展属性
     */
    public Object removeAttribute(String name) {
        return name != null ? attributes.remove(name) : null;
    }
}
