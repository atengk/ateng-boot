package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengObservabilityAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengObservabilityAutoConfiguration 自动装配单元测试")
class AtengObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengObservabilityAutoConfiguration.class));

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengObservabilityAutoConfiguration.class));

    @Test
    @DisplayName("非 Web 独立环境下默认自动装配成功并注入 ObservabilityProperties")
    void testDefaultAutoConfigurationInNonWebEnvironment() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengObservabilityAutoConfiguration.class);
            assertThat(context).hasSingleBean(ObservabilityProperties.class);

            ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getHealth().getTimeout().toSeconds()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("Web 环境下默认自动装配成功并加载配置属性")
    void testDefaultAutoConfigurationInWebEnvironment() {
        webContextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengObservabilityAutoConfiguration.class);
            assertThat(context).hasSingleBean(ObservabilityProperties.class);

            ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getMetrics().getPrometheus().isEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("当 ateng.boot.observability.enabled=false 时自动装配平滑跳过")
    void testAutoConfigurationDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengObservabilityAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(ObservabilityProperties.class);
                });
    }
}
