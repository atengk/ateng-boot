package io.github.atengk.boot.sample.sms.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.sms.dto.DelaySmsRequest;
import io.github.atengk.boot.sample.sms.dto.PlainOrIntlSmsRequest;
import io.github.atengk.boot.sample.sms.dto.SendSmsRequest;
import io.github.atengk.boot.sample.sms.service.SmsScenarioService;
import io.github.atengk.boot.sample.sms.vo.SmsBalanceVO;
import io.github.atengk.boot.sample.sms.vo.SmsDeliveryQueryVO;
import io.github.atengk.boot.sample.sms.vo.SmsScheduledReceiptVO;
import io.github.atengk.boot.sms.model.SmsResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 短信延迟定时调度、纯文本/国际号码发送、主动回执/余额查询及沙箱演练控制器
 * 严格遵循显式 Result 响应契约、DTO/VO 传输隔离与 JSR-303 前置防卫
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/sms")
@RequiredArgsConstructor
public class SmsDelayAndQueryController {

    private final SmsScenarioService smsScenarioService;

    /**
     * 提交延迟发送短信任务（两阶段校验：入队仅校验参数/渠道，到点走完整实时治理链）
     *
     * @param request 延迟调度发信传输对象
     * @return 可取消的调度回执视图对象
     */
    @PostMapping("/delay/schedule")
    public Result<SmsScheduledReceiptVO> scheduleDelayed(@Valid @RequestBody DelaySmsRequest request) {
        log.info("[SmsDelayAndQueryController] 收到延迟短信预约请求 -> 延迟秒数: {}", request.getDelaySeconds());
        return Result.success(smsScenarioService.scheduleDelayedSms(request));
    }

    /**
     * 提交指定绝对时刻定时发送短信任务
     *
     * @param request 定时发信传输对象
     * @return 可取消的调度回执视图对象
     */
    @PostMapping("/delay/at")
    public Result<SmsScheduledReceiptVO> scheduleAt(@Valid @RequestBody DelaySmsRequest request) {
        log.info("[SmsDelayAndQueryController] 收到定时短信预约请求 -> triggerAt: {}", request.getTriggerAt());
        return Result.success(smsScenarioService.scheduleAtTimeSms(request));
    }

    /**
     * 撤销取消尚未执行的延迟/定时短信任务
     *
     * @param taskId 调度任务 ID
     * @return 是否取消成功
     */
    @PostMapping("/delay/cancel")
    public Result<Boolean> cancelScheduled(@RequestParam("taskId") @NotBlank(message = "调度任务 taskId 不能为空") String taskId) {
        log.info("[SmsDelayAndQueryController] 收到取消延迟短信请求 -> taskId: {}", taskId);
        return Result.success(smsScenarioService.cancelScheduledSms(taskId));
    }

    /**
     * 发送无模板纯文本短信或 E.164 国际区号短信
     *
     * @param request 纯文本/国际号码发送传输对象
     * @return 发送结果
     */
    @PostMapping("/delay/plain-intl")
    public Result<SmsResult> sendPlainOrInternational(@Valid @RequestBody PlainOrIntlSmsRequest request) {
        log.info("[SmsDelayAndQueryController] 收到纯文本/国际短信发送请求 -> 渠道: {}", request.getChannel());
        return Result.success(smsScenarioService.sendPlainOrInternationalSms(request));
    }

    /**
     * 主动查询指定渠道单条短信的发送状态与送达回执
     *
     * @param channel  渠道名称（可空走默认渠道）
     * @param phone    目标手机号
     * @param bizId    业务流水号
     * @param sendDate 发送日期（格式 yyyy-MM-dd，可空默认当日）
     * @return 脱敏后的发送回执查询视图对象
     */
    @GetMapping("/query/status")
    public Result<SmsDeliveryQueryVO> querySendStatus(
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam("phone") @NotBlank(message = "查询手机号不能为空") String phone,
            @RequestParam(value = "bizId", required = false) String bizId,
            @RequestParam(value = "sendDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sendDate) {
        log.info("[SmsDelayAndQueryController] 主动查询短信发送状态 -> 渠道: {}, bizId: {}", channel, bizId);
        return Result.success(smsScenarioService.queryDeliveryStatus(channel, phone, bizId, sendDate));
    }

    /**
     * 只读探查指定渠道对应的云厂商账户余额或剩余条数
     *
     * @param channel 渠道名称（可空走默认渠道）
     * @return 账户余额视图对象
     */
    @GetMapping("/query/balance")
    public Result<SmsBalanceVO> queryBalance(@RequestParam(value = "channel", required = false) String channel) {
        log.info("[SmsDelayAndQueryController] 只读探查渠道账户余额 -> 渠道: {}", channel);
        return Result.success(smsScenarioService.queryChannelBalance(channel));
    }

    /**
     * 安全沙箱零外发模拟演练（模拟 log-only 沙箱阻断真实运营商外发并返回脱敏模拟回执）
     *
     * @param request 单条发信请求
     * @return 模拟投递回执
     */
    @PostMapping("/delay/sandbox-preview")
    public Result<SmsResult> sandboxDryRun(@Valid @RequestBody SendSmsRequest request) {
        log.info("[SmsDelayAndQueryController] 触发非生产安全沙箱零外发模拟演练");
        return Result.success(smsScenarioService.simulateSandboxDryRun(request));
    }
}
