package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.metrics.AtengThreadPoolMeterBinder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengMetricsAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengMetricsAutoConfiguration 自动装配单元测试")
class AtengMetricsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withConfiguration(AutoConfigurations.of(
                    AtengObservabilityAutoConfiguration.class,
                    AtengMetricsAutoConfiguration.class
            ));

    @Configuration
    static class TestConfig {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class CustomBinderConfig {
        @Bean
        public AtengThreadPoolMeterBinder customThreadPoolMeterBinder() {
            return new AtengThreadPoolMeterBinder();
        }
    }

    @Test
    @DisplayName("默认情况下自动装配 AtengThreadPoolMeterBinder Bean")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengMetricsAutoConfiguration.class);
            assertThat(context).hasSingleBean(AtengThreadPoolMeterBinder.class);
        });
    }

    @Test
    @DisplayName("当 ateng.boot.observability.metrics.thread-pool.enabled=false 时跳过装配")
    void testMetricsDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.observability.metrics.thread-pool.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengMetricsAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AtengThreadPoolMeterBinder.class);
                });
    }

    @Test
    @DisplayName("当容器已存在 AtengThreadPoolMeterBinder 时遵循 ConditionalOnMissingBean")
    void testCustomBeanOverride() {
        contextRunner
                .withUserConfiguration(CustomBinderConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengThreadPoolMeterBinder.class);
                    assertThat(context.getBean(AtengThreadPoolMeterBinder.class))
                            .isSameAs(context.getBean("customThreadPoolMeterBinder"));
                });
    }
}
