package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * 实时通信核心原生 WebSocket 消息处理器
 * <p>
 * 接收文本帧与二进制帧，托管连接建立与断开的生命周期；
 * 自动与 RealtimeSessionManager 统一会话中心对接，
 * 接收到上行消息时自动识别应用层心跳 Ping 并即时回执 Pong。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class AtengWebSocketHandler extends TextWebSocketHandler {

    /**
     * WebSocketSession 属性中存储 SessionKey 的属性键
     */
    public static final String SESSION_KEY_ATTR = "ateng.boot.realtime.sessionKey";

    /**
     * WebSocketSession 属性中存储多租户 ID 的属性键
     */
    public static final String TENANT_ID_ATTR = "ateng.boot.realtime.tenantId";

    private final RealtimeSessionManager sessionManager;

    public AtengWebSocketHandler() {
        this(null);
    }

    public AtengWebSocketHandler(RealtimeSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.debug("[Realtime] WebSocket 连接建立成功: sessionId={}, remoteAddress={}", session.getId(), session.getRemoteAddress());
        if (sessionManager != null) {
            Map<String, Object> attributes = session.getAttributes();
            SessionKey sessionKey = null;
            Long tenantId = null;
            if (attributes != null) {
                Object attrKey = attributes.get(SESSION_KEY_ATTR);
                if (attrKey instanceof SessionKey sk) {
                    sessionKey = sk;
                }
                Object attrTenant = attributes.get(TENANT_ID_ATTR);
                if (attrTenant instanceof Long tid) {
                    tenantId = tid;
                } else if (attrTenant instanceof Number num) {
                    tenantId = num.longValue();
                }
            }
            if (sessionKey == null) {
                sessionKey = SessionKey.anonymous(session.getId());
                if (attributes != null) {
                    attributes.put(SESSION_KEY_ATTR, sessionKey);
                }
            }
            RealtimeSession realtimeSession = RealtimeSession.ofWebSocket(sessionKey, session, tenantId, attributes);
            sessionManager.registerSession(realtimeSession);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.trace("[Realtime] 收到 WebSocket 文本消息: sessionId={}, payload={}", session.getId(), payload);

        // 刷新会话活跃时间
        if (sessionManager != null && session.getAttributes() != null) {
            Object attrKey = session.getAttributes().get(SESSION_KEY_ATTR);
            if (attrKey instanceof SessionKey sessionKey) {
                RealtimeSession realtimeSession = sessionManager.getSession(sessionKey);
                if (realtimeSession != null) {
                    realtimeSession.updateActiveTime();
                }
            }
        }

        // 1. 快速判定字符串字面量心跳 "ping"
        if ("ping".equalsIgnoreCase(payload.trim())) {
            session.sendMessage(new TextMessage("pong"));
            return;
        }

        // 2. 判定标准 JSON RealtimeMessage 心跳
        try {
            RealtimeMessage<?> realtimeMessage = JsonUtil.parseObject(payload, RealtimeMessage.class);
            if (realtimeMessage != null && RealtimeMessage.EVENT_PING.equalsIgnoreCase(realtimeMessage.getEvent())) {
                RealtimeMessage<String> pong = RealtimeMessage.pong();
                session.sendMessage(new TextMessage(JsonUtil.toJsonString(pong)));
                return;
            }
        } catch (Exception ignored) {
            // 非 JSON 格式或自定义消息格式，由后续分发器统一消费
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        log.debug("[Realtime] 收到 WebSocket 二进制帧: sessionId={}, payloadLength={}", session.getId(), message.getPayloadLength());
        if (sessionManager != null && session.getAttributes() != null) {
            Object attrKey = session.getAttributes().get(SESSION_KEY_ATTR);
            if (attrKey instanceof SessionKey sessionKey) {
                RealtimeSession realtimeSession = sessionManager.getSession(sessionKey);
                if (realtimeSession != null) {
                    realtimeSession.updateActiveTime();
                }
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("[Realtime] WebSocket 传输层异常: sessionId={}, error={}", session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.debug("[Realtime] WebSocket 连接已关闭: sessionId={}, status={}", session.getId(), status);
        if (sessionManager != null) {
            Map<String, Object> attributes = session.getAttributes();
            if (attributes != null) {
                Object attrKey = attributes.get(SESSION_KEY_ATTR);
                if (attrKey instanceof SessionKey sessionKey) {
                    sessionManager.removeSession(sessionKey);
                    return;
                }
            }
            sessionManager.removeSession(SessionKey.anonymous(session.getId()));
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
