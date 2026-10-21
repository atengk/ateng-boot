package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 实时通信核心原生 WebSocket 消息处理器
 * <p>
 * 接收文本帧与二进制帧，托管连接建立与断开的生命周期；
 * 接收到上行消息时，自动识别应用层心跳 Ping 并即时回执 Pong。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class AtengWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.debug("[Realtime] WebSocket 连接建立成功: sessionId={}, remoteAddress={}", session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.trace("[Realtime] 收到 WebSocket 文本消息: sessionId={}, payload={}", session.getId(), payload);

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
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("[Realtime] WebSocket 传输层异常: sessionId={}, error={}", session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.debug("[Realtime] WebSocket 连接已关闭: sessionId={}, status={}", session.getId(), status);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
