package io.github.atengk.boot.sample.realtime.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.realtime.model.RealtimeMessage;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import io.github.atengk.boot.sample.realtime.dto.SendOfflineMessageRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 离线消息可靠暂存与历史重放控制器
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/realtime/offline")
@RequiredArgsConstructor
public class RealtimeOfflineController {

    private final RealtimeTemplate realtimeTemplate;

    /**
     * 发送持久化离线消息（当用户处于离线状态时自动存入 Redisson ZSet 离线暂存箱）
     */
    @PostMapping("/send-persistent")
    public Result<Integer> sendPersistent(@Valid @RequestBody SendOfflineMessageRequest request) {
        log.info("[Sample] 发送持久化离线消息: userId={}, event={}", request.getUserId(), request.getEvent());
        RealtimeMessage<String> message = RealtimeMessage.ofPersistent(request.getEvent(), request.getContent());
        int sent = realtimeTemplate.sendToUser(request.getUserId(), message);
        return Result.success(sent);
    }

    /**
     * 一次性拉取并排空指定用户的离线暂存消息
     */
    @GetMapping("/messages")
    public Result<List<RealtimeMessage<?>>> pullOfflineMessages(@RequestParam String userId) {
        log.info("[Sample] 一次性拉取并排空离线消息: userId={}", userId);
        List<RealtimeMessage<?>> messages = realtimeTemplate.pullAndClearOfflineMessages(userId);
        return Result.success(messages);
    }

    /**
     * 查询用户未读离线消息计数（只读窥探，不修改底层 ZSet）
     */
    @GetMapping("/count")
    public Result<Integer> getOfflineMessageCount(@RequestParam String userId) {
        int count = realtimeTemplate.getOfflineMessageCount(userId);
        return Result.success(count);
    }

    /**
     * 清空指定用户的离线暂存箱
     */
    @DeleteMapping("/messages")
    public Result<Void> clearOfflineMessages(@RequestParam String userId) {
        log.info("[Sample] 清空离线暂存消息: userId={}", userId);
        realtimeTemplate.clearOfflineMessages(userId);
        return Result.success();
    }
}
