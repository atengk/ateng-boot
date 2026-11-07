package io.github.atengk.boot.sample.sms.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.sms.dto.SendVerifyCodeRequest;
import io.github.atengk.boot.sample.sms.dto.VerifyCodeCheckRequest;
import io.github.atengk.boot.sample.sms.dto.VerifyCodeInvalidateRequest;
import io.github.atengk.boot.sample.sms.service.SmsScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务验证码全生命周期治理业务领域控制器
 * 严格遵循显式 Result 响应契约与 JSR-303 分层防卫，支持夜间免打扰自动豁免
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/sms/verify-code")
@RequiredArgsConstructor
public class SmsVerifyCodeController {

    private final SmsScenarioService smsScenarioService;

    /**
     * 生成并发送业务验证码（具备夜间免打扰自动豁免与滑动窗口防刷限流）
     *
     * @param request 验证码发送请求传输对象
     * @return 统一响应体包装的生成验证码字符（供调试自验）
     */
    @PostMapping("/send")
    public Result<String> sendVerifyCode(@Valid @RequestBody SendVerifyCodeRequest request) {
        log.info("[SmsVerifyCodeController] 收到验证码生成与发送请求 -> 业务类型: {}", request.getBizType());
        String code = smsScenarioService.sendVerifyCode(request);
        return Result.success(code);
    }

    /**
     * 验证码只读预检接口（不自毁，不扣减重试次数，支持多步表单预先校验）
     *
     * @param request 验证码核验请求传输对象
     * @return 验证码是否匹配且有效
     */
    @PostMapping("/check")
    public Result<Boolean> checkVerifyCode(@Valid @RequestBody VerifyCodeCheckRequest request) {
        log.info("[SmsVerifyCodeController] 收到验证码只读预检请求 -> 业务类型: {}", request.getBizType());
        boolean passed = smsScenarioService.checkVerifyCode(request);
        return Result.success(passed);
    }

    /**
     * 核验业务验证码接口（核验通过物理自毁防重放，失败累计重试次数）
     *
     * @param request 验证码核验请求传输对象
     * @return 验证码核验是否成功通过
     */
    @PostMapping("/verify")
    public Result<Boolean> verifyCode(@Valid @RequestBody VerifyCodeCheckRequest request) {
        log.info("[SmsVerifyCodeController] 收到验证码核验并自毁请求 -> 业务类型: {}", request.getBizType());
        boolean passed = smsScenarioService.verifyCode(request);
        return Result.success(passed);
    }

    /**
     * 主动作废验证码接口（针对用户重新触发其他业务动作或主动放弃会话时调用）
     *
     * @param request 验证码作废请求传输对象
     * @return 空结果成功标识
     */
    @PostMapping("/invalidate")
    public Result<Void> invalidateVerifyCode(@Valid @RequestBody VerifyCodeInvalidateRequest request) {
        log.info("[SmsVerifyCodeController] 收到验证码主动作废请求 -> 业务类型: {}", request.getBizType());
        smsScenarioService.invalidateVerifyCode(request);
        return Result.success();
    }
}
