package io.github.atengk.boot.sample.sms.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.sms.dto.BatchSmsSendRequest;
import io.github.atengk.boot.sample.sms.dto.SendSmsRequest;
import io.github.atengk.boot.sample.sms.dto.SendVerifyCodeRequest;
import io.github.atengk.boot.sample.sms.dto.VerifyCodeCheckRequest;
import io.github.atengk.boot.sample.sms.service.SmsScenarioService;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * 短信多场景业务演示控制器
 * 严格遵循显式 Result 响应契约，入参挂载 JSR-303 格式校验
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@RestController
@RequestMapping("/api/sms")
@RequiredArgsConstructor
public class SmsScenarioController {

    private final SmsScenarioService smsScenarioService;

    /**
     * 单条短信同步发信接口
     *
     * @param request 发信请求
     * @return Result<SmsResult>
     */
    @PostMapping("/send")
    public Result<SmsResult> send(@Valid @RequestBody SendSmsRequest request) {
        SmsResult result = smsScenarioService.sendSingleSms(request);
        return Result.success(result);
    }

    /**
     * 单条短信受管异步发信接口
     *
     * @param request 发信请求
     * @return CompletableFuture<Result<SmsResult>>
     */
    @PostMapping("/send-async")
    public CompletableFuture<Result<SmsResult>> sendAsync(@Valid @RequestBody SendSmsRequest request) {
        return smsScenarioService.sendSingleSmsAsync(request)
                .thenApply(Result::success);
    }

    /**
     * 本地事务感知发信接口（演示事务提交发信与回滚防幽灵短信）
     *
     * @param request        发信请求
     * @param shouldRollback 是否模拟业务异常触发事务回滚
     * @return Result<Void>
     */
    @PostMapping("/send-tx")
    public Result<Void> sendTransactional(@Valid @RequestBody SendSmsRequest request,
                                         @RequestParam(name = "shouldRollback", defaultValue = "false") boolean shouldRollback) {
        smsScenarioService.sendTransactional(request, shouldRollback);
        return Result.success();
    }

    /**
     * 批量营销短信切片发信接口
     *
     * @param request 批量发信请求
     * @return Result<BatchSmsResult>
     */
    @PostMapping("/batch")
    public Result<BatchSmsResult> sendBatch(@Valid @RequestBody BatchSmsSendRequest request) {
        BatchSmsResult result = smsScenarioService.sendBatchSms(request);
        return Result.success(result);
    }

    /**
     * 发送业务验证码接口 (防刷限流与生命周期治理)
     *
     * @param request 验证码请求
     * @return Result<String>
     */
    @PostMapping("/verify-code/send")
    public Result<String> sendVerifyCode(@Valid @RequestBody SendVerifyCodeRequest request) {
        String code = smsScenarioService.sendVerifyCode(request);
        return Result.success(code);
    }

    /**
     * 核验业务验证码接口 (核验成功物理自毁)
     *
     * @param request 核验请求
     * @return Result<Boolean>
     */
    @PostMapping("/verify-code/verify")
    public Result<Boolean> verifyCode(@Valid @RequestBody VerifyCodeCheckRequest request) {
        boolean passed = smsScenarioService.verifyCode(request);
        return Result.success(passed);
    }

    /**
     * 获取当前所有已注册的短信渠道列表
     *
     * @return Result<Collection<SmsChannel>>
     */
    @GetMapping("/channel/list")
    public Result<Collection<SmsChannel>> listChannels() {
        return Result.success(smsScenarioService.getChannels());
    }

    /**
     * 动态注册或更新短信渠道
     *
     * @param channel 短信渠道配置
     * @return Result<Void>
     */
    @PostMapping("/channel/dynamic")
    public Result<Void> registerDynamicChannel(@RequestBody SmsChannel channel) {
        smsScenarioService.registerDynamicChannel(channel);
        return Result.success();
    }
}
