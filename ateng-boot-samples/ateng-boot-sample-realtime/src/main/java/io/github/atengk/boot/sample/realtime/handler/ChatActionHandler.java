package io.github.atengk.boot.sample.realtime.handler;

import io.github.atengk.boot.realtime.annotation.RealtimeAction;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 实时上行消息声明式处理组件
 * <p>
 * 演示使用 @RealtimeAction 声明式拦截客户端上行业务事件，
 * 并支持自适应注入 RealtimeSession、SessionKey 以及业务载荷参数。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatActionHandler {

    private final RealtimeTemplate realtimeTemplate;

    /**
     * 处理客户端点对点聊天互动
     *
     * @param session 当前上行会话
     * @param message 完整上行消息契约
     */
    @RealtimeAction("chat")
    public void handleChatMessage(RealtimeSession session, RealtimeMessage<Map<String, Object>> message) throws IOException {
        Map<String, Object> data = message.getData();
        log.info("[Sample] 收到客户端聊天消息: fromUser={}, data={}", session.getUserId(), data);

        // 回执当前用户已收到
        RealtimeMessage<String> receipt = RealtimeMessage.of("chat:ack", "Message received from " + session.getUserId());
        session.sendMessage(receipt);

        // 若携带目标用户，则通过 RealtimeTemplate 单播转发
        if (data != null && data.containsKey("targetUser")) {
            String targetUser = String.valueOf(data.get("targetUser"));
            realtimeTemplate.sendToUser(targetUser, RealtimeMessage.of("chat:received", data.get("content")));
        }
    }

    /**
     * 处理客户端加入房间/群组请求
     *
     * @param sessionKey 上行会话坐标
     * @param payload    载荷数据
     */
    @RealtimeAction("join-room")
    public void handleJoinRoom(SessionKey sessionKey, Map<String, Object> payload) {
        String roomId = payload != null ? String.valueOf(payload.get("roomId")) : "default-room";
        log.info("[Sample] 收到加入房间请求: sessionKey={}, roomId={}", sessionKey, roomId);

        realtimeTemplate.joinGroup(roomId, sessionKey);
        realtimeTemplate.sendToSession(sessionKey, RealtimeMessage.of("room:joined", "Joined room: " + roomId));
    }

    /**
     * 处理房间组播互动
     *
     * @param payload 载荷数据
     */
    @RealtimeAction("room-broadcast")
    public void handleRoomBroadcast(Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String roomId = String.valueOf(payload.get("roomId"));
        Object content = payload.get("content");
        log.info("[Sample] 房间组播转发: roomId={}, content={}", roomId, content);

        realtimeTemplate.sendToGroup(roomId, RealtimeMessage.of("room:message", content));
    }
}
