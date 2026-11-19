package io.github.atengk.boot.statemachine.interceptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 状态机 Micrometer 度量监控拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class MetricsStateMachineInterceptorTest {

    @Test
    @DisplayName("测试成功跃迁时指标计数器与计时器打标")
    void testMetricsSuccess() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsStateMachineInterceptor interceptor = new MetricsStateMachineInterceptor(registry);

        interceptor.afterTransition("WAIT_PAY", "PAID", "PAY_EVENT", "ctx", Duration.ofMillis(20));

        Counter counter = registry.find("statemachine.transition.total")
                .tag("from", "WAIT_PAY")
                .tag("to", "PAID")
                .tag("event", "PAY_EVENT")
                .tag("status", "success")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = registry.find("statemachine.transition.timer")
                .tag("from", "WAIT_PAY")
                .tag("event", "PAY_EVENT")
                .tag("status", "success")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试异常跃迁时失败计数器打标")
    void testMetricsFailure() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsStateMachineInterceptor interceptor = new MetricsStateMachineInterceptor(registry);

        interceptor.onException("WAIT_PAY", "PAY_EVENT", "ctx", new IllegalStateException("Balance insufficient"));

        Counter counter = registry.find("statemachine.transition.total")
                .tag("from", "WAIT_PAY")
                .tag("to", "ERROR")
                .tag("event", "PAY_EVENT")
                .tag("status", "failure")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("测试 registry 为 null 时安全防卫")
    void testNullRegistryDefensive() {
        MetricsStateMachineInterceptor interceptor = new MetricsStateMachineInterceptor(null);

        assertThatNoException().isThrownBy(() -> {
            interceptor.afterTransition("A", "B", "E", null, Duration.ofMillis(10));
            interceptor.onException("A", "E", null, new RuntimeException());
        });
    }

    @Test
    @DisplayName("测试携带 machineId 的指标打标包含 machine_id 维度")
    void testMetricsWithMachineIdTag() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsStateMachineInterceptor interceptor = new MetricsStateMachineInterceptor(registry);

        interceptor.afterTransition("ORDER_LIFECYCLE", "WAIT_PAY", "PAID", "PAY", "ctx", Duration.ofMillis(25));
        interceptor.onException("ORDER_LIFECYCLE", "PAID", "SHIP", "ctx", new IllegalStateException("fail"));

        Counter successCounter = registry.find("statemachine.transition.total")
                .tag("machine_id", "ORDER_LIFECYCLE")
                .tag("from", "WAIT_PAY")
                .tag("to", "PAID")
                .tag("event", "PAY")
                .tag("status", "success")
                .counter();
        assertThat(successCounter).isNotNull();
        assertThat(successCounter.count()).isEqualTo(1.0);

        Timer successTimer = registry.find("statemachine.transition.timer")
                .tag("machine_id", "ORDER_LIFECYCLE")
                .tag("from", "WAIT_PAY")
                .tag("event", "PAY")
                .tag("status", "success")
                .timer();
        assertThat(successTimer).isNotNull();
        assertThat(successTimer.count()).isEqualTo(1);

        Counter failureCounter = registry.find("statemachine.transition.total")
                .tag("machine_id", "ORDER_LIFECYCLE")
                .tag("from", "PAID")
                .tag("to", "ERROR")
                .tag("event", "SHIP")
                .tag("status", "failure")
                .counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }
}
