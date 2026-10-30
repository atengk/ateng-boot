package io.github.atengk.boot.sample.mail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.mail.model.BatchMailSendResult;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.model.MailPriority;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.BatchMailItemRequest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * 批量个性化发送邮件接口（依托 ValidList 严格传输防卫）
     *
     * @param requestList 批量邮件项列表
     * @return 批量发信聚合回执
     */
    @PostMapping("/batch")
    public Result<BatchMailSendResult> sendBatch(@Valid @RequestBody ValidList<BatchMailItemRequest> requestList) {
        List<MailMessage> messages = new ArrayList<>();
        if (requestList != null) {
            for (BatchMailItemRequest item : requestList) {
                messages.add(new MailMessage()
                        .to(item.getTo())
                        .subject(item.getSubject())
                        .text(item.getContent()));
            }
        }
        BatchMailSendResult result = mailScenarioService.sendBatchPersonalized(messages);
        return Result.success(result);
    }

    /**
     * 多语言国际化模板邮件发送接口
     *
     * @param request  发信请求
     * @param language 目标多语言标识 (如 en, zh, en_US)
     * @return 发信结果
     */
    @PostMapping("/localized-template")
    public Result<MailSendResult> sendLocalizedTemplate(@Valid @RequestBody SendMailRequest request,
                                                        @RequestParam(defaultValue = "zh_CN") String language) {
        Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
        String username = request.getUsername() != null ? request.getUsername() : "开发者";
        String verifyCode = request.getVerifyCode() != null ? request.getVerifyCode() : "888888";
        MailSendResult result = mailScenarioService.sendLocalizedVerifyCode(
                request.getTo(),
                username,
                verifyCode,
                locale
        );
        return Result.success(result);
    }

    /**
     * 管理端 SMTP 物理连通性一键测试探针接口
     *
     * @param accountName 待测试的发信账号名称
     * @return 探测是否连通
     */
    @PostMapping("/test-connection")
    public Result<Boolean> testConnection(@RequestParam @NotBlank(message = "账号标识不能为空") String accountName) {
        mailScenarioService.testConnectionOrThrow(accountName);
        return Result.success(true);
    }

    /**
     * 紧急线上高优先级告警邮件发送接口
     *
     * @param request            发信请求
     * @param requestReadReceipt 是否请求阅读回执
     * @param archiveBcc         合规归档密送
     * @return 发信回执
     */
    @PostMapping("/urgent-alert")
    public Result<MailSendResult> sendUrgentAlert(@Valid @RequestBody SendMailRequest request,
                                                  @RequestParam(defaultValue = "true") boolean requestReadReceipt,
                                                  @RequestParam(required = false) String archiveBcc) {
        MailSendResult result = mailScenarioService.sendUrgentAlert(
                request.getTo(),
                request.getSubject(),
                request.getContent(),
                MailPriority.HIGH,
                requestReadReceipt,
                archiveBcc
        );
        return Result.success(result);
    }
}
