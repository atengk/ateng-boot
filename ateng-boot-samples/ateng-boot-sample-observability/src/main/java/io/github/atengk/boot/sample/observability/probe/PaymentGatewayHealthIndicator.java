package io.github.atengk.boot.sample.observability.probe;

import io.github.atengk.boot.observability.health.AbstractSafeHealthIndicator;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

/**
 * 第三方支付网关防假死安全健康探针
 * <p>
 * 继承自 {@link AbstractSafeHealthIndicator}，演示外部高危网络调用超时硬切断与防假死降级
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Component
public class PaymentGatewayHealthIndicator extends AbstractSafeHealthIndicator {

    public PaymentGatewayHealthIndicator(ObservabilityProperties properties) {
        super(properties);
    }

    @Override
    protected String getComponentName() {
        return "paymentGateway";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        // 模拟外部第三方支付通道（微信/支付宝等）连通性探测
        builder.up()
                .withDetail("channel", "MockPayGateway")
                .withDetail("latency", "12ms")
                .withDetail("status", "UP");
    }
}
