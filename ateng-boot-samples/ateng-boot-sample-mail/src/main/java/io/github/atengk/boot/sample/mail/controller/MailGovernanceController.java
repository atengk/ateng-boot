package io.github.atengk.boot.sample.mail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.BatchAfterCommitRequest;
import io.github.atengk.boot.sample.mail.dto.ComplianceFreemarkerRequest;
import io.github.atengk.boot.sample.mail.dto.FailoverDemoRequest;
import io.github.atengk.boot.sample.mail.dto.GuardDemoResponse;
import io.github.atengk.boot.sample.mail.dto.ObservabilitySnapshotResponse;
import io.github.atengk.boot.sample.mail.dto.OutboundGuardDemoRequest;
import io.github.atengk.boot.sample.mail.dto.SandboxDemoRequest;
import io.github.atengk.boot.sample.mail.dto.SimpleTextMailRequest;
import io.github.atengk.boot.sample.mail.dto.TenantRouteMailRequest;
import io.github.atengk.boot.sample.mail.service.MailGovernanceService;
import io.github.atengk.boot.sample.mail.service.MailScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邮件高阶治理、容灾容错、安全沙箱、合规协议与可观测监控控制器
 * <p>
 * 暴露三阶出站防护（频控/日配额/幂等）、主备账号顺延容灾、批量事务感知回调、
 * 沙箱双策略隔离、Freemarker 合规渲染、多租户路由、缓存清理、异步事务发信、
 * 函数式 DSL 与健康/指标实时快照端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@RestController
@RequestMapping("/sample/mail/governance")
@RequiredArgsConstructor
@Validated
public class MailGovernanceController {

    private final MailGovernanceService mailGovernanceService;
    private final MailScenarioService mailScenarioService;

    /**
     * 演示出站频率限制（账号令牌桶触发 MAIL_RATE_LIMITED 与窗口恢复）
     *
     * @param request 出站治理请求
     * @return 治理演示执行摘要
     */
    @PostMapping("/rate-limit-demo")
    public Result<GuardDemoResponse> rateLimitDemo(@Valid @RequestBody OutboundGuardDemoRequest request) {
        return Result.success(mailGovernanceService.runRateLimitDemo(request));
    }

    /**
     * 演示自然日总配额硬熔断与 MailQuotaExceededEvent 领域事件捕获
     *
     * @param request 出站治理请求
     * @return 治理演示执行摘要
     */
    @PostMapping("/quota-demo")
    public Result<GuardDemoResponse> quotaDemo(@Valid @RequestBody OutboundGuardDemoRequest request) {
        return Result.success(mailGovernanceService.runQuotaDemo(request));
    }

    /**
     * 演示 bizId 出站幂等防重与失败自动放锁重试
     *
     * @param request 出站治理请求
     * @return 治理演示执行摘要
     */
    @PostMapping("/idempotent-demo")
    public Result<GuardDemoResponse> idempotentDemo(@Valid @RequestBody OutboundGuardDemoRequest request) {
        return Result.success(mailGovernanceService.runIdempotentDemo(request));
    }

    /**
     * 演示 backupAccounts 主备账号顺延容灾与 From 域对齐（原发件人写入 Reply-To）
     *
     * @param request 容灾演示请求
     * @return 备用通道成功回执
     */
    @PostMapping("/failover-demo")
    public Result<MailSendResult> failoverDemo(@Valid @RequestBody FailoverDemoRequest request) {
        return Result.success(mailGovernanceService.runFailoverDemo(request));
    }

    /**
     * 演示 sendBatchAfterCommit 事务提交后批量邮件合并（支持回滚取消）
     *
     * @param request 批量事务请求
     * @return 事务执行说明
     */
    @PostMapping("/batch-after-commit")
    public Result<String> batchAfterCommit(@Valid @RequestBody BatchAfterCommitRequest request) {
        return executeWithRollbackExpectation(
                () -> mailGovernanceService.sendBatchAfterCommit(request),
                request.isShouldRollback(),
                "事务已提交，sendBatchAfterCommit 已触发批量邮件合并外发",
                "业务事务已回滚，sendBatchAfterCommit 已安全取消整批幽灵邮件"
        );
    }

    /**
     * 演示测试沙箱双策略（白名单重定向清场 / 纯日志模拟）
     *
     * @param request 沙箱演示请求
     * @return 发信结果回执
     */
    @PostMapping("/sandbox-demo")
    public Result<MailSendResult> sandboxDemo(@Valid @RequestBody SandboxDemoRequest request) {
        return Result.success(mailGovernanceService.runSandboxDemo(request));
    }

    /**
     * 演示 Freemarker 模板渲染与 Return-Path / List-Unsubscribe 合规协议头
     *
     * @param request 合规渲染请求
     * @return 发信结果回执
     */
    @PostMapping("/freemarker-compliance")
    public Result<MailSendResult> freemarkerCompliance(@Valid @RequestBody ComplianceFreemarkerRequest request) {
        return Result.success(mailGovernanceService.sendFreemarkerCompliance(request));
    }

    /**
     * 演示多租户上下文（TenantContextHolder）动态账号路由发信
     *
     * @param request 多租户发信请求
     * @return 发信结果回执
     */
    @PostMapping("/tenant-route")
    public Result<MailSendResult> tenantRoute(@Valid @RequestBody TenantRouteMailRequest request) {
        return Result.success(mailGovernanceService.sendWithTenantContext(request));
    }

    /**
     * 演示清空全部动态发信账号 LRU 缓存（clearDynamicAccounts）
     *
     * @return 操作是否成功
     */
    @PostMapping("/clear-dynamic-accounts")
    public Result<Boolean> clearDynamicAccounts() {
        mailScenarioService.clearDynamicAccounts();
        return Result.success(true);
    }

    /**
     * 演示事务提交后受管异步发信（sendAsyncAfterCommit）
     *
     * @param request        发信请求
     * @param shouldRollback 是否模拟业务异常触发回滚
     * @return 执行说明
     */
    @PostMapping("/async-after-commit")
    public Result<String> asyncAfterCommit(@Valid @RequestBody SimpleTextMailRequest request,
                                           @RequestParam(defaultValue = "false") boolean shouldRollback) {
        return executeWithRollbackExpectation(
                () -> mailGovernanceService.sendAsyncAfterCommit(request, shouldRollback),
                shouldRollback,
                "事务已提交，sendAsyncAfterCommit 已派发受管异步发信任务",
                "业务事务已回滚，sendAsyncAfterCommit 已安全取消异步幽灵邮件"
        );
    }

    private Result<String> executeWithRollbackExpectation(Runnable action,
                                                         boolean shouldRollback,
                                                         String commitSuccessMessage,
                                                         String rollbackSuccessMessage) {
        try {
            action.run();
            if (shouldRollback) {
                return Result.fail("未预期：业务事务未按预期触发回滚");
            }
            return Result.success(commitSuccessMessage);
        } catch (IllegalStateException ex) {
            if (!shouldRollback) {
                throw ex;
            }
            return Result.success(rollbackSuccessMessage);
        }
    }

    /**
     * 演示函数式 DSL 极简发信（MailTemplate.send(Consumer<MailMessage>)）
     *
     * @param request 发信请求
     * @return 发信结果回执
     */
    @PostMapping("/dsl-send")
    public Result<MailSendResult> dslSend(@Valid @RequestBody SimpleTextMailRequest request) {
        return Result.success(mailGovernanceService.sendViaDsl(request));
    }

    /**
     * 查验 MailSafeHealthIndicator 健康探针状态与 Micrometer 指标快照
     *
     * @return 可观测性状态快照
     */
    @GetMapping("/observability-status")
    public Result<ObservabilitySnapshotResponse> observabilityStatus() {
        return Result.success(mailGovernanceService.getObservabilitySnapshot());
    }
}
