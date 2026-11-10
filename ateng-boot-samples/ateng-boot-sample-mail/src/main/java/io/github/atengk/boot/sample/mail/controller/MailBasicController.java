package io.github.atengk.boot.sample.mail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.HtmlContentMailRequest;
import io.github.atengk.boot.sample.mail.dto.SimpleTextMailRequest;
import io.github.atengk.boot.sample.mail.dto.UserVerificationMailRequest;
import io.github.atengk.boot.sample.mail.service.MailScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * 邮件基础通信与模板渲染控制器
 * <p>
 * 提供纯文本、HTML 富文本、Thymeleaf 动态模板及多语言国际化级联渲染发信端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/mail/basic")
@RequiredArgsConstructor
@Validated
public class MailBasicController {

    private final MailScenarioService mailScenarioService;

    /**
     * 发送简单纯文本邮件（默认通道）
     *
     * @param request 纯文本发信请求
     * @return 强类型发信回执
     */
    @PostMapping("/simple-text")
    public Result<MailSendResult> sendSimpleText(@Valid @RequestBody SimpleTextMailRequest request) {
        MailSendResult result = mailScenarioService.sendSimpleText(
                request.getTo(),
                request.getSubject(),
                request.getContent()
        );
        return Result.success(result);
    }

    /**
     * 发送富文本 HTML 内容邮件
     *
     * @param request HTML 发信请求
     * @return 强类型发信回执
     */
    @PostMapping("/html-content")
    public Result<MailSendResult> sendHtmlContent(@Valid @RequestBody HtmlContentMailRequest request) {
        MailSendResult result = mailScenarioService.sendHtmlContent(
                request.getTo(),
                request.getSubject(),
                request.getHtmlContent()
        );
        return Result.success(result);
    }

    /**
     * 发送动态 Thymeleaf 模板邮件
     *
     * @param request 用户注册/验证码发信请求
     * @return 强类型发信回执
     */
    @PostMapping("/template")
    public Result<MailSendResult> sendTemplate(@Valid @RequestBody UserVerificationMailRequest request) {
        String subject = request.getSubject() != null && !request.getSubject().isBlank()
                ? request.getSubject() : "账号安全验证码";
        MailSendResult result = mailScenarioService.sendHtmlTemplate(
                request.getTo(),
                subject,
                request.getUsername(),
                request.getVerifyCode()
        );
        return Result.success(result);
    }

    /**
     * 发送多语言国际化验证码模板邮件（级联 Locale 变体寻址）
     *
     * @param request  发信请求
     * @param language 目标多语言标识 (如 en, zh, en_US)
     * @return 强类型发信回执
     */
    @PostMapping("/i18n-template")
    public Result<MailSendResult> sendI18nTemplate(@Valid @RequestBody UserVerificationMailRequest request,
                                                  @RequestParam(defaultValue = "zh_CN") String language) {
        Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
        MailSendResult result = mailScenarioService.sendLocalizedVerifyCode(
                request.getTo(),
                request.getUsername(),
                request.getVerifyCode(),
                locale
        );
        return Result.success(result);
    }
}
