package io.github.atengk.boot.sample.sms.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.sms.dto.SendSmsRequest;
import io.github.atengk.boot.sample.sms.service.SmsScenarioService;
import io.github.atengk.boot.sms.model.SmsResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * 短信单条与事务发信业务领域控制器
 * 严格遵循显式 Result 响应契约与 JSR-303 分层防卫
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/sms/send")
@RequiredArgsConstructor
public class SmsSendController {

    private final SmsScenarioService smsScenarioService;

    /**
     * 单条短信同步发信接口
     *
     * @param request 发信请求传输对象
     * @return 统一响应体包装的短信发信结果
     */
    @PostMapping("/single")
    public Result<SmsResult> sendSingle(@Valid @RequestBody SendSmsRequest request) {
        log.info("[SmsSendController] 收到单条短信同步发送请求 -> 模板: {}", request.getTemplateId());
        SmsResult result = smsScenarioService.sendSingleSms(request);
        return Result.success(result);
    }

    /**
     * 单条短信受管异步发信接口
     *
     * @param request 发信请求传输对象
     * @return 异步统一响应体包装的短信发信结果
     */
    @PostMapping("/async")
    public CompletableFuture<Result<SmsResult>> sendAsync(@Valid @RequestBody SendSmsRequest request) {
        log.info("[SmsSendController] 收到单条短信受管异步发送请求 -> 模板: {}", request.getTemplateId());
        return smsScenarioService.sendSingleSmsAsync(request)
                .thenApply(Result::success);
    }

    /**
     * 本地事务感知发信接口（演示事务提交后发信与异常回滚防幽灵短信）
     *
     * @param request        发信请求传输对象
     * @param shouldRollback 是否模拟业务异常触发事务回滚
     * @return 空结果成功标识
     */
    @PostMapping("/tx")
    public Result<Void> sendTransactional(@Valid @RequestBody SendSmsRequest request,
                                          @RequestParam(name = "shouldRollback", defaultValue = "false") boolean shouldRollback) {
        log.info("[SmsSendController] 收到本地事务感知发信请求 -> 触发回滚: {}", shouldRollback);
        smsScenarioService.sendTransactional(request, shouldRollback);
        return Result.success();
    }

    /**
     * 模板逻辑映射发信接口（支持跨厂商多渠道逻辑模板标识自动解析物理模板ID）
     *
     * @param request 发信请求传输对象（templateId 传入渠道映射表中的逻辑模板标识）
     * @return 统一响应体包装的短信发信结果
     */
    @PostMapping("/mapping")
    public Result<SmsResult> sendWithTemplateMapping(@Valid @RequestBody SendSmsRequest request) {
        log.info("[SmsSendController] 收到模板逻辑映射发信请求 -> 逻辑模板: {}", request.getTemplateId());
        SmsResult result = smsScenarioService.sendSingleSms(request);
        return Result.success(result);
    }
}
