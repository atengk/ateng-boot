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
 * 协同通知生产可靠性与高并发并发治理场景控制器
 * 聚焦本地事务感知发信（sendAfterCommit 防幽灵通知）与受管异步非阻塞发信（sendAsync 四维追踪上下文透传）
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/notification/transaction")
@RequiredArgsConstructor
public class NotificationTransactionController {

    private final NotificationScenarioService notificationService;

    /**
     * 本地物理事务感知发信（事务成功物理 commit 后真正调用网络外呼，回滚则自动取消，100% 根除幽灵通知）
     *
     * @param request 文本通知发信请求
     * @param triggerRollback 是否模拟业务抛出异常触发事务回滚
     * @return 业务结果提示
     */
    @PostMapping("/send-after-commit")
    public Result<String> sendAfterCommit(
            @Valid @RequestBody TextMessageRequest request,
            @RequestParam(defaultValue = "false") boolean triggerRollback) {
        log.info("协同通知: 触发本地事务感知发信 [channel={}, triggerRollback={}]", request.getChannel(), triggerRollback);
        String msg = notificationService.sendTransactional(request, triggerRollback);
        return Result.success(msg);
    }

    /**
     * 受管异步非阻塞发信（接入 Core 受管线程池 notificationTaskExecutor，无损透传 MDC、用户与租户四维上下文）
     *
     * @param request 文本通知发信请求
     * @return 任务提交响应
     */
    @PostMapping("/send-async")
    public Result<String> sendAsync(@Valid @RequestBody TextMessageRequest request) {
        log.info("协同通知: 触发受管异步非阻塞发信 [channel={}]", request.getChannel());
        CompletableFuture<NotificationSendResult> future = notificationService.sendAsync(request);
        return Result.success("异步发信任务已提交受管线程池");
    }
}
