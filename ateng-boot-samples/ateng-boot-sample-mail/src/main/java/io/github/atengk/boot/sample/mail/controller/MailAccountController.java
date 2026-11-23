package io.github.atengk.boot.sample.mail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mail.model.BatchMailSendResult;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.DynamicAccountMailRequest;
import io.github.atengk.boot.sample.mail.dto.DynamicBatchMailRequest;
import io.github.atengk.boot.sample.mail.dto.SimpleTextMailRequest;
import io.github.atengk.boot.sample.mail.service.MailGovernanceService;
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

/**
 * 邮件发信账号与通道治理控制器
 * <p>
 * 提供多账号静态路由、外部介质动态账号供给懒加载、缓存热驱逐（API 与领域事件）及 SMTP 物理连通性一键探针端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/mail/account")
@RequiredArgsConstructor
@Validated
public class MailAccountController {

    private final MailScenarioService mailScenarioService;
    private final MailGovernanceService mailGovernanceService;

    /**
     * 发送指定运维通道邮件（通道隔离验证）
     *
     * @param request 发信请求
     * @return 强类型发信回执
     */
    @PostMapping("/ops-channel")
    public Result<MailSendResult> sendOpsChannel(@Valid @RequestBody SimpleTextMailRequest request) {
        MailSendResult result = mailScenarioService.sendMultiAccountOps(
                request.getTo(),
                request.getSubject(),
                request.getContent()
        );
        return Result.success(result);
    }

    /**
     * 指定外部介质动态账号发信（演示懒加载、LRU 池化复用与发件人别名覆盖）
     *
     * @param request 动态发信请求
     * @return 强类型发信回执
     */
    @PostMapping("/dynamic-send")
    public Result<MailSendResult> sendDynamicAccount(@Valid @RequestBody DynamicAccountMailRequest request) {
        MailSendResult result = mailScenarioService.sendByDynamicAccount(request);
        return Result.success(result);
    }

    /**
     * 精准驱逐指定动态账号缓存（本地单机热刷新）
     *
     * @param accountName 待驱逐的动态账号标识
     * @return 驱逐是否成功
     */
    @PostMapping("/evict-cache")
    public Result<Boolean> evictCache(@RequestParam @NotBlank(message = "账号标识不能为空") String accountName) {
        mailScenarioService.evictDynamicAccount(accountName);
        return Result.success(true);
    }

    /**
     * 广播发布动态账号驱逐领域事件（集群热刷新）
     *
     * @param accountName 待驱逐的账号标识（为空时清空全部）
     * @return 广播是否成功
     */
    @PostMapping("/broadcast-evict")
    public Result<Boolean> broadcastEvict(@RequestParam(required = false) String accountName) {
        mailScenarioService.publishEvictEvent(accountName);
        return Result.success(true);
    }

    /**
     * 管理端 SMTP 物理连通性一键测试探针
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
     * 演示 DynamicMailSession.sendBatch 动态账号批量合并发信
     *
     * @param request 动态批量发信请求
     * @return 批量聚合发信回执
     */
    @PostMapping("/dynamic-batch")
    public Result<BatchMailSendResult> sendDynamicBatch(@Valid @RequestBody DynamicBatchMailRequest request) {
        return Result.success(mailScenarioService.sendBatchByDynamicSession(request));
    }

    /**
     * 演示 XOAUTH2 动态访问令牌首次过期触发 MailAccessTokenProvider 自动刷新与单次重试
     *
     * @param request 发信请求
     * @return 刷新令牌后成功投递的强类型回执
     */
    @PostMapping("/oauth2-refresh-demo")
    public Result<MailSendResult> oauth2RefreshDemo(@Valid @RequestBody SimpleTextMailRequest request) {
        return Result.success(mailGovernanceService.runOAuth2RefreshDemo(request));
    }
}
