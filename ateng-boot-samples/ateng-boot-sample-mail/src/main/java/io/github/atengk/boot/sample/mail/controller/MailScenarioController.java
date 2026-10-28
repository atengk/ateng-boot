package io.github.atengk.boot.sample.mail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.SendMailRequest;
import io.github.atengk.boot.sample.mail.service.MailScenarioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * 邮件场景化 REST 接口控制器
 * <p>
 * 遵循显式强类型契约 {@link Result} 与传输层分层防卫标准，面向外部调用暴露邮件收发能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
@Validated
public class MailScenarioController {

    private final MailScenarioService mailScenarioService;

    /**
     * 发送简单纯文本邮件
     *
     * @param request 发信请求参数
     * @return 强类型发信回执结果
     */
    @PostMapping("/simple")
    public Result<MailSendResult> sendSimple(@Valid @RequestBody SendMailRequest request) {
        MailSendResult result = mailScenarioService.sendSimpleText(
                request.getTo(),
                request.getSubject(),
                request.getContent()
        );
        return Result.success(result);
    }

    /**
     * 发送动态模板渲染邮件
     *
     * @param request 发信请求参数
     * @return 强类型发信回执结果
     */
    @PostMapping("/template")
    public Result<MailSendResult> sendTemplate(@Valid @RequestBody SendMailRequest request) {
        String username = request.getUsername() != null ? request.getUsername() : "开发者";
        String verifyCode = request.getVerifyCode() != null ? request.getVerifyCode() : "888888";
        MailSendResult result = mailScenarioService.sendHtmlTemplate(
                request.getTo(),
                request.getSubject(),
                username,
                verifyCode
        );
        return Result.success(result);
    }

    /**
     * 发送指定运维通道邮件
     *
     * @param request 发信请求参数
     * @return 强类型发信回执结果
     */
    @PostMapping("/ops")
    public Result<MailSendResult> sendOps(@Valid @RequestBody SendMailRequest request) {
        MailSendResult result = mailScenarioService.sendMultiAccountOps(
                request.getTo(),
                request.getSubject(),
                request.getContent()
        );
        return Result.success(result);
    }

    /**
     * 受管异步发送邮件
     *
     * @param request 发信请求参数
     * @return 异步发信任务结果
     */
    @PostMapping("/async")
    public CompletableFuture<Result<MailSendResult>> sendAsync(@Valid @RequestBody SendMailRequest request) {
        return mailScenarioService.sendAsync(
                request.getTo(),
                request.getSubject(),
                request.getContent()
        ).thenApply(Result::success);
    }

    /**
     * 指定发件账号发信（支持外部数据源动态懒加载与发件人别名动态覆盖）
     *
     * @param request 发信请求参数
     * @return 强类型发信回执结果
     */
    @PostMapping("/account")
    public Result<MailSendResult> sendByAccount(@Valid @RequestBody SendMailRequest request) {
        MailSendResult result = mailScenarioService.sendByAccount(request);
        return Result.success(result);
    }

    /**
     * 精准驱逐指定动态账号缓存
     *
     * @param accountName 待驱逐的动态账号标识
     * @return 驱逐结果
     */
    @PostMapping("/evict")
    public Result<Boolean> evictDynamicAccount(@RequestParam @NotBlank(message = "账号标识不能为空") String accountName) {
        mailScenarioService.evictDynamicAccount(accountName);
        return Result.success(true);
    }

    /**
     * 广播发布动态账号驱逐领域事件
     *
     * @param accountName 待驱逐的账号标识（可为空）
     * @return 广播结果
     */
    @PostMapping("/broadcast-evict")
    public Result<Boolean> broadcastEvict(@RequestParam(required = false) String accountName) {
        mailScenarioService.publishEvictEvent(accountName);
        return Result.success(true);
    }
}
