package io.github.atengk.boot.sample.encrypt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.guard.EnforceEncryptionPolicy;
import io.github.atengk.boot.sample.encrypt.dto.IngressSimulateRequest;
import io.github.atengk.boot.sample.encrypt.dto.KeyRefreshRequest;
import io.github.atengk.boot.sample.encrypt.service.IngressGuardScenarioService;
import io.github.atengk.boot.sample.encrypt.vo.IngressRuleVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 配置热更新与防明文倒灌守卫控制器
 * <p>
 * 提供模拟配置中心热推送刷新、模拟裸明文敏感配置注入拦截验证、以及生产三道防误伤过滤链规则探查端点。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/encrypt/guard")
@RequiredArgsConstructor
public class EncryptGuardController {

    private final IngressGuardScenarioService ingressGuardScenarioService;

    /**
     * 1. 模拟配置中心热更新刷新（原子置换并清空解密缓存）
     *
     * @param request 刷新入参（为空时全量刷新）
     * @return 刷新成功标识
     */
    @PostMapping("/refresh")
    public Result<Boolean> triggerRefresh(@RequestBody(required = false) KeyRefreshRequest request) {
        if (request != null && request.getChangedKeys() != null && !request.getChangedKeys().isEmpty()) {
            log.info("[EncryptGuardController] 触发指定键局部热更新刷新: {}", request.getChangedKeys());
            ingressGuardScenarioService.triggerKeyRefresh(request.getChangedKeys());
        } else {
            log.info("[EncryptGuardController] 触发全量环境热更新刷新");
            ingressGuardScenarioService.triggerFullRefresh();
        }
        return Result.success(true);
    }

    /**
     * 2. 模拟配置中心热推送裸明文敏感属性，验证防明文倒灌守卫拦截
     *
     * @param request 模拟注入入参
     * @return 拦截结果说明
     */
    @PostMapping("/simulate-ingress")
    public Result<String> simulatePlaintextIngress(@Valid @RequestBody IngressSimulateRequest request) {
        log.info("[EncryptGuardController] 模拟动态推送敏感明文配置: key={}", request.getSensitiveKey());
        try {
            ingressGuardScenarioService.simulatePlaintextIngress(request.getSensitiveKey(), request.getPlaintextValue());
            return Result.success("Plaintext accepted (not blocked by enforce policy or whitelisted)");
        } catch (ConfigSecurityException e) {
            log.warn("[EncryptGuardController] 成功触发防明文倒灌拦截: {}", e.getMessage());
            return Result.success("Successfully intercepted plaintext ingress: " + e.getMessage());
        }
    }

    /**
     * 3. 探查防误伤安全守卫规则与免疫特征
     *
     * @return 防误伤规则展示对象
     */
    @GetMapping("/rules")
    public Result<IngressRuleVO> getIngressRules() {
        log.info("[EncryptGuardController] 探查防误伤过滤链规则与免疫特征");
        IngressRuleVO vo = IngressRuleVO.builder()
                .enforceEncrypted(false) // 样例默认自洽运行，生产配置可开启
                .sensitivePattern(EnforceEncryptionPolicy.DEFAULT_SENSITIVE_KEY_PATTERN)
                .ignoredKeys(Set.of("sample.secret.whitelist-key"))
                .protocolImmunities(List.of("http://", "https://", "classpath:", "file:", "ws://", "wss://"))
                .logLevelImmunities(List.of("DEBUG", "INFO", "WARN", "ERROR", "TRACE"))
                .build();
        return Result.success(vo);
    }
}
