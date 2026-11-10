package io.github.atengk.boot.sample.observability.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.observability.probe.PaymentGatewayHealthIndicator;
import io.github.atengk.boot.sample.observability.probe.SimulatedSlowSafeHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 防假死健康探针领域控制器
 * <p>
 * 提供自定义防假死安全健康探针执行与超时硬切断降级模拟接口
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

    public ObservabilityProbeController(
            PaymentGatewayHealthIndicator paymentGatewayHealthIndicator,
            ApplicationEventPublisher eventPublisher) {
        this.paymentGatewayHealthIndicator = paymentGatewayHealthIndicator;
        this.eventPublisher = eventPublisher;
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
     * 触发模拟慢中间件探针超时熔断与降级（演示超时硬切断并发布 HealthProbeDegradedEvent）
     */
    @GetMapping("/simulate-timeout")
    public Result<Map<String, Object>> simulateTimeout() {
        SimulatedSlowSafeHealthIndicator indicator =
                new SimulatedSlowSafeHealthIndicator(Duration.ofMillis(100), eventPublisher);
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
