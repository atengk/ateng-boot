package io.github.atengk.boot.sample.observability.probe;

import io.github.atengk.boot.observability.health.AbstractSafeHealthIndicator;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 第三方支付网关防假死安全健康探针
 * <p>
 * 继承自 {@link AbstractSafeHealthIndicator}，演示外部高危网络调用超时硬切断、受管线程池自动绑定与故障自愈对称闭环
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Component
public class PaymentGatewayHealthIndicator extends AbstractSafeHealthIndicator {

    private static final String DEFAULT_FAILURE_REASON = "模拟第三方支付通道连通性异常";

    private final AtomicBoolean simulatedDown = new AtomicBoolean(false);
    private volatile String failureReason = DEFAULT_FAILURE_REASON;

    public PaymentGatewayHealthIndicator(ObservabilityProperties properties) {
        super(properties);
    }

    /**
     * 切换本地模拟故障开关（仅供 Sample 场景演示与自愈回归测试使用）
     *
     * @param down   true 表示注入故障降级为 DOWN，false 表示恢复健康
     * @param reason 可选故障原因描述
     */
    public void setSimulatedDown(boolean down, String reason) {
        this.failureReason = (reason != null && !reason.isBlank()) ? reason : DEFAULT_FAILURE_REASON;
        this.simulatedDown.set(down);
    }

    /**
     * 当前是否处于模拟故障注入状态
     */
    public boolean isSimulatedDown() {
        return this.simulatedDown.get();
    }

    @Override
    protected String getComponentName() {
        return "paymentGateway";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        if (this.simulatedDown.get()) {
            builder.down()
                    .withDetail("channel", "MockPayGateway")
                    .withDetail("reason", this.failureReason)
                    .withDetail("status", "DOWN");
            return;
        }
        // 模拟外部第三方支付通道（微信/支付宝等）连通性探测
        builder.up()
                .withDetail("channel", "MockPayGateway")
                .withDetail("latency", "12ms")
                .withDetail("status", "UP");
    }
}
