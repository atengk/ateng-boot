package io.github.atengk.boot.observability.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ObservabilityEnvironmentPostProcessor 环境后处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("ObservabilityEnvironmentPostProcessor 环境后处理器单元测试")
class ObservabilityEnvironmentPostProcessorTest {

    @Test
    @DisplayName("默认情况下向环境注入云原生基线配置属性")
    void testDefaultEnvironmentInjected() {
        ObservabilityEnvironmentPostProcessor processor = new ObservabilityEnvironmentPostProcessor();
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains(ObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,prometheus");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.health.livenessstate.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.health.readinessstate.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.endpoint.health.group.liveness.include")).isEqualTo("livenessState,ping");
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("always");
        assertThat(environment.getProperty("management.info.env.enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("当 k8s-isolation-enabled=false 时，跳过 liveness 分组基线注入")
    void testK8sIsolationDisabled() {
        ObservabilityEnvironmentPostProcessor processor = new ObservabilityEnvironmentPostProcessor();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testConfig", Map.of(
                "ateng.boot.observability.health.k8s-isolation-enabled", "false"
        )));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains(ObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty("management.endpoint.health.group.liveness.include")).isNull();
    }

    @Test
    @DisplayName("用户显式配置 liveness 分组属性时，享有最高优先级覆盖默认基线")
    void testK8sIsolationUserOverride() {
        ObservabilityEnvironmentPostProcessor processor = new ObservabilityEnvironmentPostProcessor();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("userConfig", Map.of(
                "management.endpoint.health.group.liveness.include", "customProbe,ping"
        )));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("management.endpoint.health.group.liveness.include")).isEqualTo("customProbe,ping");
    }

    @Test
    @DisplayName("当全局开关 ateng.boot.observability.enabled=false 时，跳过环境属性注入")
    void testDisabledEnvironmentSkipped() {
        ObservabilityEnvironmentPostProcessor processor = new ObservabilityEnvironmentPostProcessor();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testConfig", Map.of("ateng.boot.observability.enabled", "false")));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains(ObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("用户显式配置高于默认基线属性（最低优先级注入保证）")
    void testUserPropertiesOverrideDefaults() {
        ObservabilityEnvironmentPostProcessor processor = new ObservabilityEnvironmentPostProcessor();
        StandardEnvironment environment = new StandardEnvironment();
        // 用户显式设置仅暴露 health
        environment.getPropertySources().addFirst(new MapPropertySource("userConfig", Map.of(
                "management.endpoints.web.exposure.include", "health"
        )));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }

    @Test
    @DisplayName("后处理器优先级与幂等执行测试")
    void testOrderAndIdempotency() {
        ObservabilityEnvironmentPostProcessor processor = new ObservabilityEnvironmentPostProcessor();
        assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 10);

        StandardEnvironment environment = new StandardEnvironment();
        processor.postProcessEnvironment(environment, null);
        processor.postProcessEnvironment(environment, null);

        // 仅包含一个对应的属性源
        long count = environment.getPropertySources().stream()
                .filter(ps -> ObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME.equals(ps.getName()))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
