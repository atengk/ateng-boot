package io.github.atengk.boot.sample.observability.probe;

import io.github.atengk.boot.observability.health.AbstractSafeHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;

/**
 * 模拟慢中间件健康探针（用于触发防假死超时熔断）
 *
 * @author Ateng
 * @since 2026-11-10
 */
public class SimulatedSlowSafeHealthIndicator extends AbstractSafeHealthIndicator {

    public SimulatedSlowSafeHealthIndicator(Duration timeout, ApplicationEventPublisher eventPublisher) {
        super(timeout);
        if (eventPublisher != null) {
            setApplicationEventPublisher(eventPublisher);
        }
    }

    @Override
    protected String getComponentName() {
        return "simulatedSlowProbe";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        // 故意休眠超过配置的超时阈值以触发硬切断
        Thread.sleep(300);
        builder.up();
    }
}
