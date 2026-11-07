package io.github.atengk.boot.sample.sms.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.sms.dto.BlacklistAddRequest;
import io.github.atengk.boot.sample.sms.dto.SendSmsRequest;
import io.github.atengk.boot.sample.sms.service.SmsScenarioService;
import io.github.atengk.boot.sample.sms.vo.SmsSendAuditVO;
import io.github.atengk.boot.sms.model.SmsResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 短信出站防卫（黑名单/幂等防重/免打扰）与审计事件业务领域控制器
 * 严格遵循显式 Result 响应契约与 JSR-303 分层防卫
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/sms/guard")
@RequiredArgsConstructor
public class SmsGuardAndCallbackController {

    private final SmsScenarioService smsScenarioService;

    /**
     * 查询出站黑名单手机号列表
     *
     * @return 黑名单手机号码集合
     */
    @GetMapping("/blacklist")
    public Result<Set<String>> getBlacklist() {
        log.info("[SmsGuardAndCallbackController] 查询出站黑名单列表");
        Set<String> blacklist = smsScenarioService.getBlacklist();
        return Result.success(blacklist);
    }

    /**
     * 添加手机号码至出站黑名单
     *
     * @param request 黑名单添加请求传输对象
     * @return 空结果成功标识
     */
    @PostMapping("/blacklist/add")
    public Result<Void> addBlacklist(@Valid @RequestBody BlacklistAddRequest request) {
        log.info("[SmsGuardAndCallbackController] 添加手机号至出站黑名单 -> 原因: {}", request.getReason());
        smsScenarioService.addBlacklist(request.getPhone(), request.getReason());
        return Result.success();
    }

    /**
     * 从出站黑名单移除手机号码
     *
     * @param phone 目标手机号 (E.164 或标准国内手机号)
     * @return 空结果成功标识
     */
    @PostMapping("/blacklist/remove")
    public Result<Void> removeBlacklist(
            @RequestParam(name = "phone")
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = "^(\\+?86)?1[3-9]\\d{9}$", message = "手机号格式不正确")
            String phone) {
        log.info("[SmsGuardAndCallbackController] 从出站黑名单移除手机号");
        smsScenarioService.removeBlacklist(phone);
        return Result.success();
    }

    /**
     * 一键清空出站黑名单
     *
     * @return 空结果成功标识
     */
    @PostMapping("/blacklist/clear")
    public Result<Void> clearBlacklist() {
        log.info("[SmsGuardAndCallbackController] 清空出站黑名单");
        smsScenarioService.clearBlacklist();
        return Result.success();
    }

    /**
     * 业务防重幂等拦截发信接口（请求体需附带 bizId）
     *
     * @param request 发信请求传输对象（需包含唯一 bizId）
     * @return 统一响应体包装的短信发信结果
     */
    @PostMapping("/idempotent")
    public Result<SmsResult> sendIdempotent(@Valid @RequestBody SendSmsRequest request) {
        log.info("[SmsGuardAndCallbackController] 收到防重幂等发信请求 -> bizId: {}", request.getBizId());
        SmsResult result = smsScenarioService.sendSingleSms(request);
        return Result.success(result);
    }

    /**
     * 夜间免打扰静默与紧急放行发信接口（若当前在免打扰时段且 urgent 为 false 则静默拦截，urgent 为 true 紧急放行）
     *
     * @param request 发信请求传输对象
     * @return 统一响应体包装的短信发信结果
     */
    @PostMapping("/quiet-hours")
    public Result<SmsResult> sendWithQuietHours(@Valid @RequestBody SendSmsRequest request) {
        log.info("[SmsGuardAndCallbackController] 收到夜间免打扰策略发信请求 -> urgent: {}", request.isUrgent());
        SmsResult result = smsScenarioService.sendSingleSms(request);
        return Result.success(result);
    }

    /**
     * 查询全链路发信审计事件流水
     *
     * @return 发信审计视图对象列表
     */
    @GetMapping("/audit-events")
    public Result<List<SmsSendAuditVO>> getAuditEvents() {
        log.info("[SmsGuardAndCallbackController] 查询全链路发信审计事件流水");
        List<SmsSendAuditVO> events = smsScenarioService.getAuditEvents();
        return Result.success(events);
    }
}
