package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.dispatcher.RealtimeMessageDispatcher;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.ratelimit.RateLimitDecision;
import io.github.atengk.boot.realtime.ratelimit.SessionRateLimiter;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * 实时通信核心原生 WebSocket 消息处理器
 * <p>
 * 接收文本帧、二进制帧与心跳 Pong 帧，托管连接建立与断开的生命周期；
 * 自动与 RealtimeSessionManager 统一会话中心对接，
 * 接收到上行消息时自动执行会话级滑动窗口限流防刷与阶梯防御，
 * 自动识别应用层心跳 Ping 并即时回执 Pong，
 * 业务消息通过 RealtimeMessageDispatcher 统一路由分发至 &#64;RealtimeAction 处理方法。
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
    private final RealtimeMessageDispatcher dispatcher;
    private final RealtimeProperties properties;

    public AtengWebSocketHandler() {
        this(null, null, null);
    }

    public AtengWebSocketHandler(RealtimeSessionManager sessionManager) {
        this(sessionManager, null, null);
    }

    public AtengWebSocketHandler(RealtimeSessionManager sessionManager, RealtimeMessageDispatcher dispatcher) {
        this(sessionManager, dispatcher, null);
    }

    public AtengWebSocketHandler(RealtimeSessionManager sessionManager,
                                 RealtimeMessageDispatcher dispatcher,
                                 RealtimeProperties properties) {
        this.sessionManager = sessionManager;
        this.dispatcher = dispatcher;
        this.properties = properties;
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
            if (properties != null && properties.getWebsocket() != null
                    && properties.getWebsocket().getRateLimit() != null
                    && properties.getWebsocket().getRateLimit().isEnabled()) {
                realtimeSession.setRateLimiter(new SessionRateLimiter(properties.getWebsocket().getRateLimit()));
            }
            sessionManager.registerSession(realtimeSession);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.trace("[Realtime] 收到 WebSocket 文本消息: sessionId={}, payload={}", session.getId(), payload);

        // 刷新活跃时间戳
        RealtimeSession realtimeSession = resolveSession(session);
        if (realtimeSession != null) {
            realtimeSession.updateActiveTime();
        }

        // 1. 会话级上行流量限制检查与阶梯防御
        if (realtimeSession != null && realtimeSession.getRateLimiter() != null) {
            RateLimitDecision decision = realtimeSession.getRateLimiter().tryAcquire();
            if (decision == RateLimitDecision.CLOSE) {
                log.warn("[Realtime] 会话持续超限触发熔断切断: sessionKey={}, sessionId={}",
                        realtimeSession.getSessionKey(), session.getId());
                try {
                    RealtimeMessage<Map<String, Object>> errorMsg = RealtimeMessage.of(RealtimeMessage.EVENT_ERROR,
                            Map.of("code", 429, "message", "Rate limit exceeded. Connection terminated."));
                    session.sendMessage(new TextMessage(JsonUtil.toJsonString(errorMsg)));
                } catch (Throwable ignored) {
                }
                realtimeSession.close(RealtimeCloseStatus.RATE_LIMIT_EXCEEDED);
                return;
            } else if (decision == RateLimitDecision.WARNING) {
                log.warn("[Realtime] 会话上行流量超限丢弃请求帧: sessionKey={}, sessionId={}",
                        realtimeSession.getSessionKey(), session.getId());
                RealtimeMessage<Map<String, Object>> errorMsg = RealtimeMessage.of(RealtimeMessage.EVENT_ERROR,
                        Map.of("code", 429, "message", "Rate limit exceeded. Please slow down."));
                session.sendMessage(new TextMessage(JsonUtil.toJsonString(errorMsg)));
                return;
            }
        }

        // 2. 快速判定字符串字面量心跳 "ping"
        if ("ping".equalsIgnoreCase(payload.trim())) {
            session.sendMessage(new TextMessage("pong"));
            return;
        }

        // 3. 判定标准 JSON RealtimeMessage 心跳
        RealtimeMessage<?> realtimeMessage = null;
        try {
            realtimeMessage = JsonUtil.parseObject(payload, RealtimeMessage.class);
            if (realtimeMessage != null && RealtimeMessage.EVENT_PING.equalsIgnoreCase(realtimeMessage.getEvent())) {
                RealtimeMessage<String> pong = RealtimeMessage.pong();
                session.sendMessage(new TextMessage(JsonUtil.toJsonString(pong)));
                return;
            }
        } catch (Exception ignored) {
            // 非 JSON 格式或自定义消息
        }

        // 4. 上行业务事件通过 RealtimeMessageDispatcher 统一路由调度
        if (dispatcher != null && realtimeSession != null && realtimeMessage != null) {
            dispatcher.dispatch(realtimeSession, realtimeMessage);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        log.debug("[Realtime] 收到 WebSocket 二进制帧: sessionId={}, payloadLength={}", session.getId(), message.getPayloadLength());
        RealtimeSession realtimeSession = resolveSession(session);
        if (realtimeSession != null) {
            realtimeSession.updateActiveTime();
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        log.trace("[Realtime] 收到 WebSocket 协议 Pong 帧: sessionId={}", session.getId());
        RealtimeSession realtimeSession = resolveSession(session);
        if (realtimeSession != null) {
            realtimeSession.updateActiveTime();
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
                    sessionManager.removeSession(sessionKey, status);
                    return;
                }
            }
            sessionManager.removeSession(SessionKey.anonymous(session.getId()), status);
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private RealtimeSession resolveSession(WebSocketSession session) {
        if (sessionManager != null && session != null && session.getAttributes() != null) {
            Object attrKey = session.getAttributes().get(SESSION_KEY_ATTR);
            if (attrKey instanceof SessionKey sessionKey) {
                return sessionManager.getSession(sessionKey);
            }
        }
        return null;
    }
}
