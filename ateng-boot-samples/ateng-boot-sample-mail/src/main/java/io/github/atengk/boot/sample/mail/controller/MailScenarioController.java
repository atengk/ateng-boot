package io.github.atengk.boot.sample.mail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.SendMailRequest;
import io.github.atengk.boot.sample.mail.service.MailScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
