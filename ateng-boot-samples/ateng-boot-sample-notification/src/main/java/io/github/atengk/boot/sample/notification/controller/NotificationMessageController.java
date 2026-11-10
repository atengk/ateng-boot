package io.github.atengk.boot.sample.notification.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.sample.notification.dto.CardMessageRequest;
import io.github.atengk.boot.sample.notification.dto.ImageMessageRequest;
import io.github.atengk.boot.sample.notification.dto.LinkMessageRequest;
import io.github.atengk.boot.sample.notification.dto.MarkdownMessageRequest;
import io.github.atengk.boot.sample.notification.dto.RawMessageRequest;
import io.github.atengk.boot.sample.notification.dto.TextMessageRequest;
import io.github.atengk.boot.sample.notification.service.NotificationScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 协同通知五阶消息模型场景控制器
 * 聚焦纯文本、富文本 Markdown、多端交互卡片、一等公民图文链接、安全图片与平台原生逃生舱消息发信
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/notification/message")
@RequiredArgsConstructor
public class NotificationMessageController {

    private final NotificationScenarioService notificationService;

    /**
     * 发送纯文本通知（支持指定渠道与精准 @提醒）
     *
     * @param request 纯文本发信请求
     * @return 强类型发信结果契约
     */
    @PostMapping("/text")
    public Result<NotificationSendResult> sendText(@Valid @RequestBody TextMessageRequest request) {
        log.info("协同通知: 发送纯文本消息 [channel={}, atAll={}]", request.getChannel(), request.getAtAll());
        NotificationSendResult result = notificationService.sendText(request);
        return Result.success(result);
    }

    /**
     * 发送富文本 Markdown 通知（企微表格自动降级、钉钉/飞书原生渲染）
     *
     * @param request Markdown 发信请求
     * @return 强类型发信结果契约
     */
    @PostMapping("/markdown")
    public Result<NotificationSendResult> sendMarkdown(@Valid @RequestBody MarkdownMessageRequest request) {
        log.info("协同通知: 发送 Markdown 消息 [channel={}, title={}]", request.getChannel(), request.getTitle());
        NotificationSendResult result = notificationService.sendMarkdown(request);
        return Result.success(result);
    }

    /**
     * 发送互动卡片通知（支持 11 色主题顶栏与横向/纵向操作按钮排版）
     *
     * @param request 互动卡片发信请求
     * @return 强类型发信结果契约
     */
    @PostMapping("/card")
    public Result<NotificationSendResult> sendCard(@Valid @RequestBody CardMessageRequest request) {
        log.info("协同通知: 发送互动卡片消息 [channel={}, title={}, headerColor={}, btnOrientation={}]",
                request.getChannel(), request.getTitle(), request.getHeaderColor(), request.getBtnOrientation());
        NotificationSendResult result = notificationService.sendCard(request);
        return Result.success(result);
    }

    /**
     * 发送轻量图文链接通知（自适应转译为钉钉 Link、企微 News 单图文与飞书 Interactive 卡片）
     *
     * @param request 图文链接发信请求
     * @return 强类型发信结果契约
     */
    @PostMapping("/link")
    public Result<NotificationSendResult> sendLink(@Valid @RequestBody LinkMessageRequest request) {
        log.info("协同通知: 发送图文链接消息 [channel={}, title={}]", request.getChannel(), request.getTitle());
        NotificationSendResult result = notificationService.sendLink(request);
        return Result.success(result);
    }

    /**
     * 发送图片通知（支持 Base64 与本地图片路径，严格执行零字节与存在性卫语句防卫）
     *
     * @param request 图片发信请求
     * @return 强类型发信结果契约
     */
    @PostMapping("/image")
    public Result<NotificationSendResult> sendImage(@Valid @RequestBody ImageMessageRequest request) {
        log.info("协同通知: 发送图片消息 [channel={}, hasPath={}]", request.getChannel(), request.getImagePath() != null);
        NotificationSendResult result = notificationService.sendImage(request);
        return Result.success(result);
    }

    /**
     * 发送平台原始特化报文（逃生舱机制，直接透传原生 JSON 载荷）
     *
     * @param request 原生特化发信请求
     * @return 强类型发信结果契约
     */
    @PostMapping("/raw")
    public Result<NotificationSendResult> sendRaw(@Valid @RequestBody RawMessageRequest request) {
        log.info("协同通知: 发送原生特化逃生舱消息 [channel={}]", request.getChannel());
        NotificationSendResult result = notificationService.sendRaw(request);
        return Result.success(result);
    }
}
