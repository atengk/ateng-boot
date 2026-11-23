package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.dispatcher.RealtimeBinaryMessageListener;
import io.github.atengk.boot.realtime.dispatcher.RealtimeMessageDispatcher;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.ratelimit.RateLimitDecision;
import io.github.atengk.boot.realtime.ratelimit.SessionRateLimiter;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.realtime.spi.RealtimeOfflineMessageProvider;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.List;
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
    private final ObjectProvider<RealtimeBinaryMessageListener> binaryListeners;
    private final ObjectProvider<RealtimeOfflineMessageProvider> offlineMessageProvider;

    public AtengWebSocketHandler() {
        this(null, null, null, null);
    }

    public AtengWebSocketHandler(RealtimeSessionManager sessionManager) {
        this(sessionManager, null, null, null);
    }

    public AtengWebSocketHandler(RealtimeSessionManager sessionManager, RealtimeMessageDispatcher dispatcher) {
        this(sessionManager, dispatcher, null, null);
    }

    public AtengWebSocketHandler(RealtimeSessionManager sessionManager,
                                 RealtimeMessageDispatcher dispatcher,
                                 RealtimeProperties properties) {
        this(sessionManager, dispatcher, properties, null);
    }

    public AtengWebSocketHandler(RealtimeSessionManager sessionManager,
                                 RealtimeMessageDispatcher dispatcher,
                                 RealtimeProperties properties,
                                 ObjectProvider<RealtimeBinaryMessageListener> binaryListeners) {
        this(sessionManager, dispatcher, properties, binaryListeners, null);
    }

    public AtengWebSocketHandler(RealtimeSessionManager sessionManager,
                                 RealtimeMessageDispatcher dispatcher,
                                 RealtimeProperties properties,
                                 ObjectProvider<RealtimeBinaryMessageListener> binaryListeners,
                                 ObjectProvider<RealtimeOfflineMessageProvider> offlineMessageProvider) {
        this.sessionManager = sessionManager;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.binaryListeners = binaryListeners;
        this.offlineMessageProvider = offlineMessageProvider;
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
            boolean registered = sessionManager.registerSession(realtimeSession);
            if (registered) {
                pushOfflineMessagesOnConnect(realtimeSession);
            }
        }
    }

    private void pushOfflineMessagesOnConnect(RealtimeSession realtimeSession) {
        if (offlineMessageProvider == null || properties == null || properties.getOffline() == null) {
            return;
        }
        if (!properties.getOffline().isEnabled() || !properties.getOffline().isAutoPushOnConnect()) {
            return;
        }
        SessionKey sessionKey = realtimeSession.getSessionKey();
        if (sessionKey == null || sessionKey.isAnonymous()
                || sessionKey.getUserId() == null || sessionKey.getUserId().isBlank()) {
            return;
        }
        RealtimeOfflineMessageProvider provider = offlineMessageProvider.getIfAvailable();
        if (provider == null) {
            return;
        }
        String userId = sessionKey.getUserId();
        List<RealtimeMessage<?>> offlineMessages;
        try {
            offlineMessages = provider.pullAndClearOfflineMessages(userId);
        } catch (Exception ex) {
            log.warn("[Realtime] WebSocket 建连拉取离线消息失败: userId={}, sessionId={}, error={}",
                    userId, realtimeSession.getClientId(), ex.getMessage());
            return;
        }
        if (offlineMessages == null || offlineMessages.isEmpty()) {
            return;
        }
        for (int i = 0; i < offlineMessages.size(); i++) {
            RealtimeMessage<?> offlineMessage = offlineMessages.get(i);
            if (offlineMessage == null) {
                continue;
            }
            try {
                realtimeSession.sendMessage(offlineMessage);
            } catch (Exception ex) {
                log.warn("[Realtime] WebSocket 建连自动补发离线消息失败，正在回存剩余未投递消息: userId={}, sessionId={}, remaining={}, error={}",
                        userId, realtimeSession.getClientId(), offlineMessages.size() - i, ex.getMessage());
                for (int j = i; j < offlineMessages.size(); j++) {
                    RealtimeMessage<?> undelivered = offlineMessages.get(j);
                    if (undelivered != null) {
                        try {
                            provider.saveOfflineMessage(userId, undelivered);
                        } catch (Exception saveEx) {
                            log.warn("[Realtime] 回存未投递离线消息失败: userId={}, messageId={}, error={}",
                                    userId, undelivered.getId(), saveEx.getMessage());
                        }
                    }
                }
                break;
            }
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

        // 1. 快速判定字符串字面量心跳 "ping"（心跳放行，不计入上行业务限流）
        if ("ping".equalsIgnoreCase(payload.trim())) {
            session.sendMessage(new TextMessage("pong"));
            return;
        }

        // 2. 判定标准 JSON RealtimeMessage 心跳
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

        // 3. 会话级上行流量限制检查与阶梯防御（心跳放行后，仅对业务请求进行频控）
        if (realtimeSession != null && realtimeSession.getRateLimiter() != null) {
            RateLimitDecision decision = realtimeSession.getRateLimiter().tryAcquire();
            if (decision == RateLimitDecision.CLOSE) {
                log.warn("[Realtime] 会话持续超限触发熔断切断: sessionKey={}, sessionId={}",
                        realtimeSession.getSessionKey(), session.getId());
                try {
                    RealtimeMessage<Map<String, Object>> errorMsg = RealtimeMessage.of(RealtimeMessage.EVENT_ERROR,
                            Map.of("code", 429, "message", "Rate limit exceeded. Connection terminated."));
                    session.sendMessage(new TextMessage(JsonUtil.toJsonString(errorMsg)));
                } catch (Throwable e) {
                    log.debug("[Realtime] 发送限流熔断告警帧失败: sessionId={}, error={}", session.getId(), e.getMessage());
                }
                realtimeSession.close(RealtimeCloseStatus.RATE_LIMIT_EXCEEDED);
                return;
            } else if (decision == RateLimitDecision.WARNING) {
                log.warn("[Realtime] 会话上行流量超限丢弃请求帧: sessionKey={}, sessionId={}",
                        realtimeSession.getSessionKey(), session.getId());
                try {
                    RealtimeMessage<Map<String, Object>> errorMsg = RealtimeMessage.of(RealtimeMessage.EVENT_ERROR,
                            Map.of("code", 429, "message", "Rate limit exceeded. Please slow down."));
                    session.sendMessage(new TextMessage(JsonUtil.toJsonString(errorMsg)));
                } catch (Throwable e) {
                    log.debug("[Realtime] 发送限流告警帧失败: sessionId={}, error={}", session.getId(), e.getMessage());
                }
                return;
            }
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

        // 1. 会话级上行流量限制检查与阶梯防御
        if (realtimeSession != null && realtimeSession.getRateLimiter() != null) {
            RateLimitDecision decision = realtimeSession.getRateLimiter().tryAcquire();
            if (decision == RateLimitDecision.CLOSE) {
                log.warn("[Realtime] 会话持续超限触发熔断切断: sessionKey={}, sessionId={}",
                        realtimeSession.getSessionKey(), session.getId());
                realtimeSession.close(RealtimeCloseStatus.RATE_LIMIT_EXCEEDED);
                return;
            } else if (decision == RateLimitDecision.WARNING) {
                log.warn("[Realtime] 会话上行流量超限丢弃请求二进制帧: sessionKey={}, sessionId={}",
                        realtimeSession.getSessionKey(), session.getId());
                return;
            }
        }

        // 2. 提取二进制字节数据
        ByteBuffer byteBuffer = message.getPayload();
        byte[] payloadBytes;
        if (byteBuffer.hasArray()) {
            byte[] array = byteBuffer.array();
            int offset = byteBuffer.arrayOffset() + byteBuffer.position();
            int length = byteBuffer.remaining();
            if (offset == 0 && length == array.length) {
                payloadBytes = array;
            } else {
                payloadBytes = new byte[length];
                System.arraycopy(array, offset, payloadBytes, 0, length);
            }
        } else {
            payloadBytes = new byte[byteBuffer.remaining()];
            byteBuffer.duplicate().get(payloadBytes);
        }

        // 3. 正交派发给已注册的 RealtimeBinaryMessageListener SPI 监听器（贯通四维快照与全链路追踪）
        if (binaryListeners != null && realtimeSession != null) {
            String traceId = IdUtil.fastSimpleUUID();
            MDC.put("traceId", traceId);
            if (realtimeSession.getSessionKey() != null && realtimeSession.getSessionKey().getUserId() != null) {
                UserContextHolder.set(realtimeSession.getSessionKey().getUserId());
            }
            if (realtimeSession.getTenantId() != null) {
                TenantContextHolder.setTenantId(realtimeSession.getTenantId());
            }
            try {
                binaryListeners.orderedStream().forEach(listener -> {
                    try {
                        listener.onBinaryMessage(realtimeSession, payloadBytes);
                    } catch (Throwable t) {
                        log.warn("[Realtime] RealtimeBinaryMessageListener 执行异常: listener={}, sessionKey={}, error={}",
                                listener.getClass().getName(), realtimeSession.getSessionKey(), t.getMessage(), t);
                    }
                });
            } finally {
                MDC.remove("traceId");
                UserContextHolder.clear();
                TenantContextHolder.clear();
            }
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
