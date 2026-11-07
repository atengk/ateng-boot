package io.github.atengk.boot.sample.sms.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.sms.dto.DynamicChannelRegisterRequest;
import io.github.atengk.boot.sample.sms.dto.SendSmsRequest;
import io.github.atengk.boot.sample.sms.service.SmsScenarioService;
import io.github.atengk.boot.sample.sms.vo.SmsChannelVO;
import io.github.atengk.boot.sample.sms.vo.SmsCircuitBreakerStatusVO;
import io.github.atengk.boot.sms.model.SmsResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

/**
 * 短信多渠道动态治理、负载均衡与熔断运维控制器
 * 严格遵循显式 Result 响应契约与脱敏视图输出
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/sms/channel")
@RequiredArgsConstructor
public class SmsChannelController {

    private final SmsScenarioService smsScenarioService;

    /**
     * 获取当前所有已注册的短信渠道列表（严格脱敏 VO 视图，零机密泄露）
     *
     * @return 渠道视图集合
     */
    @GetMapping("/list")
    public Result<Collection<SmsChannelVO>> listChannels() {
        log.info("[SmsChannelController] 查询当前已注册短信渠道视图列表");
        Collection<SmsChannelVO> channels = smsScenarioService.getChannels();
        return Result.success(channels);
    }

    /**
     * 运行时动态注册或热更新短信渠道
     *
     * @param request 动态渠道注册请求传输对象
     * @return 空结果成功标识
     */
    @PostMapping("/register")
    public Result<Void> registerChannel(@Valid @RequestBody DynamicChannelRegisterRequest request) {
        log.info("[SmsChannelController] 收到动态注册短信渠道请求 -> 渠道: {}, 服务商: {}",
                request.getName(), request.getSupplier());
        smsScenarioService.registerDynamicChannel(request);
        return Result.success();
    }

    /**
     * 运行时动态注销短信渠道
     *
     * @param channelName 待注销渠道唯一名称
     * @return 空结果成功标识
     */
    @PostMapping("/unregister")
    public Result<Void> unregisterChannel(@RequestParam(name = "channelName") String channelName) {
        log.info("[SmsChannelController] 收到动态注销短信渠道请求 -> 渠道: {}", channelName);
        smsScenarioService.unregisterChannel(channelName);
        return Result.success();
    }

    /**
     * 平滑加权轮询负载均衡发信接口
     *
     * @param request 发信请求传输对象
     * @return 统一响应体包装的短信发信结果
     */
    @PostMapping("/load-balance")
    public Result<SmsResult> sendLoadBalanced(@Valid @RequestBody SendSmsRequest request) {
        log.info("[SmsChannelController] 收到平滑加权轮询负载均衡发信请求 -> 模板: {}", request.getTemplateId());
        SmsResult result = smsScenarioService.sendLoadBalanced(request);
        return Result.success(result);
    }

    /**
     * 查询渠道健康度熔断状态
     *
     * @return 各渠道熔断状态详情字典
     */
    @GetMapping("/circuit-breaker")
    public Result<Map<String, SmsCircuitBreakerStatusVO>> getCircuitBreakerStatus() {
        log.info("[SmsChannelController] 查询渠道健康度熔断状态");
        Map<String, SmsCircuitBreakerStatusVO> status = smsScenarioService.getCircuitBreakerStatus();
        return Result.success(status);
    }

    /**
     * 一键重置渠道熔断状态为 CLOSED
     *
     * @param channelName 指定渠道名称（不传或为空时重置全部渠道）
     * @return 空结果成功标识
     */
    @PostMapping("/circuit-breaker/reset")
    public Result<Void> resetCircuitBreaker(@RequestParam(name = "channelName", required = false) String channelName) {
        log.info("[SmsChannelController] 收到渠道熔断重置请求 -> 渠道: {}", channelName);
        smsScenarioService.resetCircuitBreaker(channelName);
        return Result.success();
    }
}
