package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.aspect.ObservedMetricAspect;
import io.github.atengk.boot.observability.metrics.AtengThreadPoolMeterBinder;
import io.github.atengk.boot.observability.metrics.MetricsTemplate;
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
    static class CustomConfig {
        @Bean
        public AtengThreadPoolMeterBinder customThreadPoolMeterBinder() {
            return new AtengThreadPoolMeterBinder();
        }

        @Bean
        public MetricsTemplate customMetricsTemplate(MeterRegistry meterRegistry) {
            return new MetricsTemplate(meterRegistry);
        }
    }

    @Test
    @DisplayName("默认情况下自动装配 MetricsTemplate、ObservedMetricAspect 与 AtengThreadPoolMeterBinder Bean")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengMetricsAutoConfiguration.class);
            assertThat(context).hasSingleBean(MetricsTemplate.class);
            assertThat(context).hasSingleBean(ObservedMetricAspect.class);
            assertThat(context).hasSingleBean(AtengThreadPoolMeterBinder.class);
        });
    }

    @Test
    @DisplayName("当 ateng.boot.observability.metrics.thread-pool.enabled=false 时仅跳过线程池绑定器装配")
    void testThreadPoolMetricsDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.observability.metrics.thread-pool.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengMetricsAutoConfiguration.class);
                    assertThat(context).hasSingleBean(MetricsTemplate.class);
                    assertThat(context).hasSingleBean(ObservedMetricAspect.class);
                    assertThat(context).doesNotHaveBean(AtengThreadPoolMeterBinder.class);
                });
    }

    @Test
    @DisplayName("当 ateng.boot.observability.metrics.observed.enabled=false 时仅跳过切面装配")
    void testObservedAspectDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.observability.metrics.observed.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengMetricsAutoConfiguration.class);
                    assertThat(context).hasSingleBean(MetricsTemplate.class);
                    assertThat(context).doesNotHaveBean(ObservedMetricAspect.class);
                    assertThat(context).hasSingleBean(AtengThreadPoolMeterBinder.class);
                });
    }

    @Test
    @DisplayName("当 ateng.boot.observability.enabled=false 时全局跳过度量体系自动装配")
    void testGlobalDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengMetricsAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(MetricsTemplate.class);
                    assertThat(context).doesNotHaveBean(ObservedMetricAspect.class);
                    assertThat(context).doesNotHaveBean(AtengThreadPoolMeterBinder.class);
                });
    }

    @Test
    @DisplayName("当容器已存在用户自定义 Bean 时遵循 ConditionalOnMissingBean")
    void testCustomBeanOverride() {
        contextRunner
                .withUserConfiguration(CustomConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengThreadPoolMeterBinder.class);
                    assertThat(context.getBean(AtengThreadPoolMeterBinder.class))
                            .isSameAs(context.getBean("customThreadPoolMeterBinder"));

                    assertThat(context).hasSingleBean(MetricsTemplate.class);
                    assertThat(context.getBean(MetricsTemplate.class))
                            .isSameAs(context.getBean("customMetricsTemplate"));
                });
    }
}
