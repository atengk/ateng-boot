package io.github.atengk.boot.sample.realtime.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import io.github.atengk.boot.sample.realtime.dto.PushBroadcastRequest;
import io.github.atengk.boot.sample.realtime.dto.PushUserMessageRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 实时通信消息推送与多通道单播/广播控制器
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/realtime/push")
@RequiredArgsConstructor
public class RealtimePushController {

    private final RealtimeTemplate realtimeTemplate;

    /**
     * 向指定用户单播消息（目标多端在线时自适应扇出，离线时若配置开启则转入离线箱）
     */
    @PostMapping("/user")
    public Result<Integer> sendToUser(@Valid @RequestBody PushUserMessageRequest request) {
        log.info("[Sample] 触发用户单播推送: userId={}, event={}", request.getUserId(), request.getEvent());
        RealtimeMessage<String> message = RealtimeMessage.of(request.getEvent(), request.getContent());
        if (StringUtils.hasText(request.getDeviceType()) && StringUtils.hasText(request.getClientId())) {
            SessionKey key = SessionKey.of(request.getUserId(), request.getDeviceType(), request.getClientId());
            boolean success = realtimeTemplate.sendToSession(key, message);
            return Result.success(success ? 1 : 0);
        }
        int count = realtimeTemplate.sendToUser(request.getUserId(), message);
        return Result.success(count);
    }

    /**
     * 定向专线单播：仅当指定端点为 WebSocket 通道时投递
     */
    @PostMapping("/websocket")
    public Result<Boolean> sendViaWebSocket(@RequestParam String userId,
                                            @RequestParam(defaultValue = "default") String clientId,
                                            @RequestParam String message) {
        log.info("[Sample] 触发 WebSocket 专线单播: userId={}, clientId={}", userId, clientId);
        boolean success = sendViaDedicatedChannel(userId, clientId, "notice:ws", true, message);
        return Result.success(success);
    }

    /**
     * 定向专线单播：仅当指定端点为 Server-Sent Events (SSE) 通道时投递
     */
    @PostMapping("/sse")
    public Result<Boolean> sendViaSse(@RequestParam String userId,
                                      @RequestParam(defaultValue = "default") String clientId,
                                      @RequestParam String message) {
        log.info("[Sample] 触发 SSE 专线单播: userId={}, clientId={}", userId, clientId);
        boolean success = sendViaDedicatedChannel(userId, clientId, "notice:sse", false, message);
        return Result.success(success);
    }

    /**
     * 向指定租户下的全域在线会话组播下发消息
     */
    @PostMapping("/tenant")
    public Result<Integer> sendToTenant(@RequestParam Long tenantId, @RequestParam String message) {
        log.info("[Sample] 触发租户组播推送: tenantId={}", tenantId);
        int count = realtimeTemplate.sendToTenant(tenantId, RealtimeMessage.of("notice:tenant", message));
        return Result.success(count);
    }

    /**
     * 全服跨节点广播公告（由去中心化 Redis Pub/Sub 总线自动跨节点扩散）
     */
    @PostMapping("/broadcast")
    public Result<Integer> broadcast(@Valid @RequestBody PushBroadcastRequest request) {
        log.info("[Sample] 触发跨节点广播: event={}, tenantId={}", request.getEvent(), request.getTenantId());
        RealtimeMessage<String> message = RealtimeMessage.of(request.getEvent(), request.getContent());
        int count;
        if (request.getTenantId() != null) {
            count = realtimeTemplate.sendToTenant(request.getTenantId(), message);
        } else {
            count = realtimeTemplate.sendToAll(message);
        }
        return Result.success(count);
    }

    private boolean sendViaDedicatedChannel(String userId, String clientId, String event, boolean isWebSocket, String content) {
        SessionKey key = SessionKey.of(userId, clientId);
        RealtimeMessage<String> realtimeMessage = RealtimeMessage.of(event, content);
        return isWebSocket ? realtimeTemplate.sendViaWebSocket(key, realtimeMessage) : realtimeTemplate.sendViaSse(key, realtimeMessage);
    }
}
