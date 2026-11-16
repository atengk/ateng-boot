package io.github.atengk.boot.sample.observability.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.observability.autoconfigure.AtengHealthAutoConfiguration;
import io.github.atengk.boot.sample.observability.probe.PaymentGatewayHealthIndicator;
import io.github.atengk.boot.sample.observability.probe.SimulatedSlowSafeHealthIndicator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 防假死健康探针领域控制器
 * <p>
 * 提供自定义防假死安全健康探针执行、故障注入与自愈恢复切换以及超时硬切断降级模拟接口
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/observability/probe")
public class ObservabilityProbeController {

    private final PaymentGatewayHealthIndicator paymentGatewayHealthIndicator;
    private final ApplicationEventPublisher eventPublisher;
    private final Executor healthProbeExecutor;

    public ObservabilityProbeController(
            PaymentGatewayHealthIndicator paymentGatewayHealthIndicator,
            ApplicationEventPublisher eventPublisher,
            @Qualifier(AtengHealthAutoConfiguration.HEALTH_PROBE_EXECUTOR_BEAN_NAME)
            ObjectProvider<Executor> healthProbeExecutorProvider) {
        this.paymentGatewayHealthIndicator = paymentGatewayHealthIndicator;
        this.eventPublisher = eventPublisher;
        this.healthProbeExecutor = healthProbeExecutorProvider.getIfAvailable();
    }

    /**
     * 执行第三方支付网关安全健康探针探测
     */
    @GetMapping("/payment-gateway")
    public Result<Map<String, Object>> getPaymentGatewayHealth() {
        Health health = paymentGatewayHealthIndicator.health();
        return Result.success(toHealthData(health));
    }

    /**
     * 注入支付网关故障并触发即时探测（演示 Readiness 降级与 HealthProbeDegradedEvent 发布）
     *
     * @param reason 可选故障原因说明
     * @return 降级后的健康快照
     */
    @PostMapping("/payment-gateway/degrade")
    public Result<Map<String, Object>> degradePaymentGateway(
            @RequestParam(value = "reason", required = false) String reason) {
        paymentGatewayHealthIndicator.setSimulatedDown(true, reason);
        Health health = paymentGatewayHealthIndicator.health();
        return Result.success(toHealthData(health));
    }

    /**
     * 恢复支付网关健康状态并触发即时探测（演示自愈恢复与 HealthProbeRecoveredEvent 对称广播）
     *
     * @return 自愈恢复后的健康快照
     */
    @PostMapping("/payment-gateway/recover")
    public Result<Map<String, Object>> recoverPaymentGateway() {
        paymentGatewayHealthIndicator.setSimulatedDown(false, null);
        Health health = paymentGatewayHealthIndicator.health();
        return Result.success(toHealthData(health));
    }

    /**
     * 触发模拟慢中间件探针超时熔断与降级（接入受管线程池执行并发布 HealthProbeDegradedEvent）
     */
    @GetMapping("/simulate-timeout")
    public Result<Map<String, Object>> simulateTimeout() {
        SimulatedSlowSafeHealthIndicator indicator =
                new SimulatedSlowSafeHealthIndicator(Duration.ofMillis(100), healthProbeExecutor, eventPublisher);
        Health health = indicator.health();
        return Result.success(toHealthData(health));
    }

    /**
     * 将 Actuator Health 实体安全转译为键值对数据（消除代码异味并防 NPE）
     */
    private Map<String, Object> toHealthData(Health health) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (health != null) {
            data.put("status", health.getStatus() != null ? health.getStatus().getCode() : "UNKNOWN");
            data.put("details", health.getDetails());
        }
        return data;
    }
}
