package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AbstractSafeHealthIndicator 防假死基类单元测试
 * 验证正常 UP、延迟超时切断降级 UNKNOWN、异常抛出降级 DOWN 及保底防御
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AbstractSafeHealthIndicator 防假死基类单元测试")
class AbstractSafeHealthIndicatorTest {

    @Test
    @DisplayName("正常探测：子类正常返回并打标，返回 UP")
    void testNormalProbeUp() {
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofSeconds(1)) {
            @Override
            protected void doCheck(Health.Builder builder) {
                builder.up().withDetail("component", "mock-subsystem").withDetail("latency", "1ms");
            }
        };

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("component", "mock-subsystem");
        assertThat(health.getDetails()).containsEntry("latency", "1ms");
    }

    @Test
    @DisplayName("显式降级探测：子类显式声明 UNKNOWN 状态，原样保留并返回")
    void testProbeExplicitUnknown() {
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofSeconds(1)) {
            @Override
            protected void doCheck(Health.Builder builder) {
                builder.unknown().withDetail("info", "explicit unknown");
            }
        };

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("info", "explicit unknown");
    }

    @Test
    @DisplayName("超时熔断：子类执行耗时超出阈值（100ms 阈值注入 500ms 延迟），立即切断降级返回 UNKNOWN")
    void testProbeTimeoutDegradation() {
        Duration timeout = Duration.ofMillis(100);
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(timeout) {
            @Override
            protected void doCheck(Health.Builder builder) throws Exception {
                Thread.sleep(500);
                builder.up();
            }
        };

        long start = System.currentTimeMillis();
        Health health = indicator.health();
        long elapsed = System.currentTimeMillis() - start;

        // 验证快速切断（远小于 500ms，在 100ms~300ms 之间切断）
        assertThat(elapsed).isLessThan(450);
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails())
                .containsEntry("status", "TIMEOUT")
                .containsEntry("timeout", timeout.toString())
                .containsEntry("reason", "Probe execution timed out");
    }

    @Test
    @DisplayName("异常阻断：子类探测发生不可逆异常，转译返回 DOWN 并保留因果原因")
    void testProbeExceptionDown() {
        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(Duration.ofSeconds(1)) {
            @Override
            protected void doCheck(Health.Builder builder) {
                throw new IllegalStateException("磁盘 IO 阻塞故障");
            }
        };

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "磁盘 IO 阻塞故障");
    }

    @Test
    @DisplayName("配置属性解析与自定义线程池构造验证")
    void testPropertiesAndCustomExecutor() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getHealth().setTimeout(Duration.ofMillis(300));
        Executor executor = Executors.newSingleThreadExecutor();

        AbstractSafeHealthIndicator indicator = new AbstractSafeHealthIndicator(properties, executor) {
            @Override
            protected void doCheck(Health.Builder builder) {
                builder.up().withDetail("custom", true);
            }
        };

        assertThat(indicator.getTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(indicator.getExecutor()).isSameAs(executor);

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("custom", true);
    }
}
