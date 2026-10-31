package io.github.atengk.boot.sample.notification.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.sample.notification.dto.CardMessageRequest;
import io.github.atengk.boot.sample.notification.dto.LinkMessageRequest;
import io.github.atengk.boot.sample.notification.dto.MarkdownMessageRequest;
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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 企业协同通知通信场景演示控制器
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
     * 发送纯文本通知（支持指定渠道与统一 @人）
     */
    @PostMapping("/text")
    public Result<NotificationSendResult> sendText(@Valid @RequestBody TextMessageRequest request) {
        NotificationSendResult result = notificationService.sendText(request);
        return Result.success(result);
    }

    /**
     * 发送富文本 Markdown 通知（企微表格自动降级、钉钉/飞书原生渲染）
     */
    @PostMapping("/markdown")
    public Result<NotificationSendResult> sendMarkdown(@Valid @RequestBody MarkdownMessageRequest request) {
        NotificationSendResult result = notificationService.sendMarkdown(request);
        return Result.success(result);
    }

    /**
     * 发送互动卡片通知（支持不同主题色与跳转操作按钮）
     */
    @PostMapping("/card")
    public Result<NotificationSendResult> sendCard(@Valid @RequestBody CardMessageRequest request) {
        NotificationSendResult result = notificationService.sendCard(request);
        return Result.success(result);
    }

    /**
     * 发送轻量图文链接通知（自适应转译为钉钉 Link、企微 News 单图文与飞书 Interactive 卡片）
     */
    @PostMapping("/link")
    public Result<NotificationSendResult> sendLink(@Valid @RequestBody LinkMessageRequest request) {
        NotificationSendResult result = notificationService.sendLink(request);
        return Result.success(result);
    }

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

    /**
     * 演示 NotificationContextHolder 动态切源发信
     */
    @PostMapping("/switch-channel")
    public Result<NotificationSendResult> switchChannel(
            @RequestParam String targetChannel,
            @Valid @RequestBody TextMessageRequest request) {
        NotificationSendResult result = notificationService.sendWithSwitchedChannel(targetChannel, request);
        return Result.success(result);
    }

    /**
     * 动态模板渲染发信演示
     */
    @PostMapping("/template")
    public Result<NotificationSendResult> sendTemplate(
            @RequestParam(required = false) String channel,
            @RequestParam String templateContent,
            @RequestBody Map<String, Object> variables) {
        NotificationSendResult result = notificationService.sendTemplate(channel, templateContent, variables);
        return Result.success(result);
    }

    /**
     * 原始特化逃生舱发信演示
     */
    @PostMapping("/raw")
    public Result<NotificationSendResult> sendRaw(
            @RequestParam(required = false) String channel,
            @RequestBody Map<String, Object> rawPayload) {
        NotificationSendResult result = notificationService.sendRaw(channel, rawPayload);
        return Result.success(result);
    }

    /**
     * 图片通知发信演示
     */
    @PostMapping("/image")
    public Result<NotificationSendResult> sendImage(
            @RequestParam(required = false) String channel,
            @RequestParam String base64,
            @RequestParam String md5) {
        NotificationSendResult result = notificationService.sendImage(channel, base64, md5);
        return Result.success(result);
    }
}
