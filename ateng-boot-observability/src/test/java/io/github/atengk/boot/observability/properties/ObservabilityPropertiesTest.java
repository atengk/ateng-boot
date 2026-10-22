package io.github.atengk.boot.observability.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ObservabilityProperties 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("ObservabilityProperties 配置属性单元测试")
class ObservabilityPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(ObservabilityProperties.class)
    static class TestConfig {
    }

    @Test
    @DisplayName("验证 ObservabilityProperties 默认配置属性与生产合理值")
    void testDefaultProperties() {
        ObservabilityProperties properties = new ObservabilityProperties();

        assertThat(properties.isEnabled()).isTrue();

        // 度量指标默认配置
        assertThat(properties.getMetrics()).isNotNull();
        assertThat(properties.getMetrics().getPrometheus().isEnabled()).isTrue();
        assertThat(properties.getMetrics().getThreadPool().isEnabled()).isTrue();

        // 健康探针默认配置
        assertThat(properties.getHealth()).isNotNull();
        assertThat(properties.getHealth().getTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getHealth().getStorage().isEnabled()).isTrue();
        assertThat(properties.getHealth().getRocketmq().isEnabled()).isTrue();
        assertThat(properties.getHealth().getRealtime().isEnabled()).isTrue();
        assertThat(properties.getHealth().getDynamicDatasource().isEnabled()).isTrue();

        // 端点安全默认配置
        assertThat(properties.getSecurity()).isNotNull();
        assertThat(properties.getSecurity().isLockdownEnabled()).isTrue();
        assertThat(properties.getSecurity().getToken()).isEmpty();
    }

    @Test
    @DisplayName("通过 ApplicationContextRunner 验证 YAML 属性动态注入与强类型 Duration 解析")
    void testCustomPropertiesBinding() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.observability.enabled=true",
                        "ateng.boot.observability.metrics.prometheus.enabled=false",
                        "ateng.boot.observability.metrics.thread-pool.enabled=true",
                        "ateng.boot.observability.health.timeout=5s",
                        "ateng.boot.observability.health.storage.enabled=false",
                        "ateng.boot.observability.health.rocketmq.enabled=false",
                        "ateng.boot.observability.security.lockdown-enabled=true",
                        "ateng.boot.observability.security.token=SEC_TOKEN_123456"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ObservabilityProperties.class);
                    ObservabilityProperties props = context.getBean(ObservabilityProperties.class);

                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getMetrics().getPrometheus().isEnabled()).isFalse();
                    assertThat(props.getMetrics().getThreadPool().isEnabled()).isTrue();
                    assertThat(props.getHealth().getTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(props.getHealth().getStorage().isEnabled()).isFalse();
                    assertThat(props.getHealth().getRocketmq().isEnabled()).isFalse();
                    assertThat(props.getHealth().getRealtime().isEnabled()).isTrue();
                    assertThat(props.getSecurity().isLockdownEnabled()).isTrue();
                    assertThat(props.getSecurity().getToken()).isEqualTo("SEC_TOKEN_123456");
                });
    }
}
