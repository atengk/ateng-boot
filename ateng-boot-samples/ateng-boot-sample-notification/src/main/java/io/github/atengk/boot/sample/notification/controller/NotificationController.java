package io.github.atengk.boot.sample.notification.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.sample.notification.dto.TextMessageRequest;
import io.github.atengk.boot.sample.notification.service.NotificationScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * 企业协同通知通信过渡控制器（待工单 #867 迁移事务与异步端点后废除）
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationScenarioService notificationService;

    /**
     * 受管异步非阻塞发信（MDC 上下文无损透传）
     */
    @PostMapping("/async")
    public Result<String> sendAsync(@Valid @RequestBody TextMessageRequest request) {
        CompletableFuture<NotificationSendResult> future = notificationService.sendAsync(request);
        return Result.success("异步发信任务已提交受管线程池");
    }

    /**
     * 本地事务感知发信（事务成功物理 commit 后发信，回滚自动取消防幽灵消息）
     */
    @PostMapping("/transactional")
    public Result<String> sendTransactional(
            @Valid @RequestBody TextMessageRequest request,
            @RequestParam(defaultValue = "false") boolean triggerRollback) {
        String msg = notificationService.sendTransactional(request, triggerRollback);
        return Result.success(msg);
    }
}
