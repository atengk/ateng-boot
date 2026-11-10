package io.github.atengk.boot.sample.notification.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.sample.notification.dto.ChannelSwitchRequest;
import io.github.atengk.boot.sample.notification.dto.FailoverDemoRequest;
import io.github.atengk.boot.sample.notification.dto.TemplateMessageRequest;
import io.github.atengk.boot.sample.notification.service.NotificationScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 协同通知渠道拓扑治理与高可用容灾场景控制器
 * 聚焦线程级动态切源发信、动态模板插值渲染与主备渠道顺延故障转移（Cascade Failover）
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/notification/channel")
@RequiredArgsConstructor
public class NotificationChannelController {

    private final NotificationScenarioService notificationService;

    /**
     * 演示 NotificationContextHolder 线程级动态切源发信
     *
     * @param request 动态切源请求
     * @return 强类型发信结果契约
     */
    @PostMapping("/switch")
    public Result<NotificationSendResult> switchChannel(@Valid @RequestBody ChannelSwitchRequest request) {
        log.info("协同通知: 触发线程级动态切源发信 [targetChannel={}]", request.getTargetChannel());
        NotificationSendResult result = notificationService.sendWithSwitchedChannel(request);
        return Result.success(result);
    }

    /**
     * 动态模板变量渲染发信演示
     *
     * @param request 模板发信请求
     * @return 强类型发信结果契约
     */
    @PostMapping("/template")
    public Result<NotificationSendResult> sendTemplate(@Valid @RequestBody TemplateMessageRequest request) {
        log.info("协同通知: 触发动态模板渲染发信 [channel={}, template={}]", request.getChannel(), request.getTemplateContent());
        NotificationSendResult result = notificationService.sendTemplate(request);
        return Result.success(result);
    }

    /**
     * 演示主备渠道顺延故障转移（Cascade Failover）
     *
     * @param request 故障转移演示请求
     * @return 实际执行发信的结果（包含顺延成功的最终备用渠道与平台）
     */
    @PostMapping("/failover")
    public Result<NotificationSendResult> sendWithFailover(@Valid @RequestBody FailoverDemoRequest request) {
        log.info("协同通知: 触发主备渠道顺延故障转移发信 [primaryChannel={}]", request.getPrimaryChannel());
        NotificationSendResult result = notificationService.sendWithFailover(request);
        return Result.success(result);
    }
}
