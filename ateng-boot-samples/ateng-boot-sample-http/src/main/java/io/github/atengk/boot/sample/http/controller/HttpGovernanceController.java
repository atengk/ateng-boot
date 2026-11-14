package io.github.atengk.boot.sample.http.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.http.dto.ErrorTranslationVO;
import io.github.atengk.boot.sample.http.dto.LoggingDemoVO;
import io.github.atengk.boot.sample.http.dto.RegistryInspectVO;
import io.github.atengk.boot.sample.http.dto.RetrySimulationVO;
import io.github.atengk.boot.sample.http.dto.WechatNativeOrderRequest;
import io.github.atengk.boot.sample.http.dto.WechatPaymentVO;
import io.github.atengk.boot.sample.http.service.ExternalPaymentScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP 治理与安全防卫业务控制器
 * 提供外部非信任公网敏感头剥离、瞬态 503 指数退避重试、出站 cURL 脱敏日志、错误体转译与多实例注册自省体验
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Slf4j
@RestController
@RequestMapping("/sample/http/governance")
@RequiredArgsConstructor
public class HttpGovernanceController {

    private final ExternalPaymentScenarioService externalPaymentScenarioService;

    /**
     * 1. 外部微信 Native 统一下单并验证非信任域内网私有敏感头物理剥离
     */
    @PostMapping("/wechat/pay")
    public Result<WechatPaymentVO> createWechatOrder(@Valid @RequestBody WechatNativeOrderRequest request) {
        WechatPaymentVO response = externalPaymentScenarioService.createWechatOrder(request);
        return Result.success(response);
    }

    /**
     * 2. 模拟瞬态 503 故障触发弹性指数退避重试演练
     */
    @GetMapping("/retry/flaky")
    public Result<RetrySimulationVO> simulateFlakyRetry(
            @RequestParam(value = "failTimes", defaultValue = "2") int failTimes) {
        RetrySimulationVO response = externalPaymentScenarioService.simulateFlakyRetry(failTimes);
        return Result.success(response);
    }

    /**
     * 3. 出站 cURL 敏感凭据自动脱敏日志演示
     */
    @GetMapping("/logging/curl-demo")
    public Result<LoggingDemoVO> triggerMaskedLoggingDemo(
            @RequestParam(value = "token", defaultValue = "sk-sample-secret-token") String token,
            @RequestParam(value = "password", defaultValue = "123456") String password) {
        LoggingDemoVO response = externalPaymentScenarioService.triggerMaskedLoggingDemo(token, password);
        return Result.success(response);
    }

    /**
     * 4. 远端错误体结构化反序列化与 HttpResultCode 因果转译演示
     */
    @GetMapping("/error-translate")
    public Result<ErrorTranslationVO> demonstrateErrorTranslation(
            @RequestParam(value = "code", defaultValue = "422") int code) {
        ErrorTranslationVO response = externalPaymentScenarioService.demonstrateErrorTranslation(code);
        return Result.success(response);
    }

    /**
     * 5. RestClientRegistry 多实例、mTLS 与代理治理元数据探针
     */
    @GetMapping("/registry-inspect")
    public Result<RegistryInspectVO> inspectRegistryMetadata() {
        RegistryInspectVO response = externalPaymentScenarioService.inspectRegistryMetadata();
        return Result.success(response);
    }
}
