package io.github.atengk.boot.sample.mail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mail.model.BatchMailSendResult;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.SalarySlipBatchRequest;
import io.github.atengk.boot.sample.mail.dto.SimpleTextMailRequest;
import io.github.atengk.boot.sample.mail.dto.SystemAlertMailRequest;
import io.github.atengk.boot.sample.mail.service.MailScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * 邮件可靠投递、批量并发、事务感知与告警治理控制器
 * <p>
 * 提供受控并发批量邮件合并、受管异步发信、本地事务感知（{@code sendAfterCommit} 防幽灵邮件）及高优先级合规审计告警端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/mail/reliable")
@RequiredArgsConstructor
@Validated
public class MailReliableController {

    private final MailScenarioService mailScenarioService;

    /**
     * 批量个性化合并发送员工工资条通知（依托 ValidList 严格传输防卫与 Semaphore 受控并发）
     *
     * @param request 批量工资条请求
     * @return 批量聚合发信回执
     */
    @PostMapping("/batch-salary")
    public Result<BatchMailSendResult> sendSalaryBatch(@Valid @RequestBody SalarySlipBatchRequest request) {
        BatchMailSendResult result = mailScenarioService.sendSalaryBatch(request);
        return Result.success(result);
    }

    /**
     * 受管异步发送邮件（Core 线程池驱动与 MDC 四维上下文透传）
     *
     * @param request 发信请求
     * @return 异步发信任务结果
     */
    @PostMapping("/async-send")
    public CompletableFuture<Result<MailSendResult>> sendAsync(@Valid @RequestBody SimpleTextMailRequest request) {
        return mailScenarioService.sendAsync(
                request.getTo(),
                request.getSubject(),
                request.getContent()
        ).thenApply(Result::success);
    }

    /**
     * 本地事务感知发信（事务正常提交：物理发信正常触发）
     *
     * @param request 发信请求
     * @return 事务提交结果说明
     */
    @PostMapping("/transaction-commit")
    public Result<String> sendTransactionCommit(@Valid @RequestBody SimpleTextMailRequest request) {
        mailScenarioService.sendTransactional(
                request.getTo(),
                request.getSubject(),
                request.getContent(),
                false
        );
        return Result.success("事务已物理提交，sendAfterCommit 成功触发邮件外发");
    }

    /**
     * 本地事务感知发信（事务发生异常回滚：自动防幽灵邮件取消发信）
     *
     * @param request 发信请求
     * @return 事务回滚结果说明
     */
    @PostMapping("/transaction-rollback")
    public Result<String> sendTransactionRollback(@Valid @RequestBody SimpleTextMailRequest request) {
        try {
            mailScenarioService.sendTransactional(
                    request.getTo(),
                    request.getSubject(),
                    request.getContent(),
                    true
            );
        } catch (IllegalStateException e) {
            // 捕获模拟的业务异常，证明事务发生回滚
            return Result.success("业务事务已回滚，sendAfterCommit 自动安全取消，成功阻止幽灵邮件外发");
        }
        return Result.fail("未预期：事务未按预期触发回滚");
    }

    /**
     * 发送高优先级紧急线上故障告警邮件（强类型优先级标头、阅读回执与合规归档密送）
     *
     * @param request 告警请求
     * @return 强类型发信回执
     */
    @PostMapping("/urgent-alert")
    public Result<MailSendResult> sendUrgentAlert(@Valid @RequestBody SystemAlertMailRequest request) {
        MailSendResult result = mailScenarioService.sendUrgentAlert(request);
        return Result.success(result);
    }
}
