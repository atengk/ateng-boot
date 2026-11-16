package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.aspect.ObservedMetricAspect;
import io.github.atengk.boot.observability.metrics.AtengMetricsRegistryCustomizer;
import io.github.atengk.boot.observability.metrics.AtengThreadPoolMeterBinder;
import io.github.atengk.boot.observability.metrics.MetricsTemplate;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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

        @Bean
        public AtengMetricsRegistryCustomizer customMetricsRegistryCustomizer(
                ObservabilityProperties properties, Environment environment) {
            return new AtengMetricsRegistryCustomizer(properties, environment);
        }
    }

    @Test
    @DisplayName("默认情况下自动装配 MetricsTemplate、ObservedMetricAspect、AtengThreadPoolMeterBinder 与 AtengMetricsRegistryCustomizer Bean")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengMetricsAutoConfiguration.class);
            assertThat(context).hasSingleBean(MetricsTemplate.class);
            assertThat(context).hasSingleBean(ObservedMetricAspect.class);
            assertThat(context).hasSingleBean(AtengThreadPoolMeterBinder.class);
            assertThat(context).hasSingleBean(AtengMetricsRegistryCustomizer.class);
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
                    assertThat(context).doesNotHaveBean(AtengMetricsRegistryCustomizer.class);
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

                    assertThat(context).hasSingleBean(AtengMetricsRegistryCustomizer.class);
                    assertThat(context.getBean(AtengMetricsRegistryCustomizer.class))
                            .isSameAs(context.getBean("customMetricsRegistryCustomizer"));
                });
    }

    @Test
    @DisplayName("自动装配后的 AtengMetricsRegistryCustomizer 在 SimpleMeterRegistry 容器中透明生效")
    void testSimpleMeterRegistryCustomizedByAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        AtengObservabilityAutoConfiguration.class,
                        AtengMetricsAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.application.name=billing-service",
                        "ateng.boot.observability.metrics.common-tags.env=staging",
                        "ateng.boot.observability.metrics.max-tag-value-length=16",
                        "ateng.boot.observability.metrics.max-meter-cardinality=2",
                        "ateng.boot.observability.metrics.deny-metric-prefixes[0]=noise."
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengMetricsRegistryCustomizer.class);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry).isInstanceOf(SimpleMeterRegistry.class);

                    Counter c1 = registry.counter("biz.tx", "tenant", "tenant-very-long-name-12345");
                    c1.increment();
                    assertThat(c1.getId().getTag("application")).isEqualTo("billing-service");
                    assertThat(c1.getId().getTag("env")).isEqualTo("staging");
                    assertThat(c1.getId().getTag("tenant")).isEqualTo("tenant-very-long");

                    registry.counter("biz.tx", "tenant", "t2").increment();
                    registry.counter("biz.tx", "tenant", "t3").increment();
                    assertThat(registry.find("biz.tx").counters()).hasSize(2);

                    registry.counter("noise.ignored").increment();
                    assertThat(registry.find("noise.ignored").counters()).isEmpty();
                });
    }

    @Test
    @DisplayName("自动装配后的 AtengMetricsRegistryCustomizer 在 PrometheusMeterRegistry 容器中透明生效")
    void testPrometheusMeterRegistryCustomizedByAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class,
                        AtengObservabilityAutoConfiguration.class,
                        AtengMetricsAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.application.name=pay-service",
                        "ateng.boot.observability.metrics.common-tags.zone=cn-north",
                        "ateng.boot.observability.metrics.max-tag-value-length=12",
                        "ateng.boot.observability.metrics.max-meter-cardinality=1",
                        "ateng.boot.observability.metrics.deny-metric-prefixes[0]=internal."
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengMetricsRegistryCustomizer.class);
                    PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);

                    registry.counter("pay.orders", "orderId", "ORD-20261116-9999").increment();
                    registry.counter("pay.orders", "orderId", "ORD-2").increment();
                    registry.counter("internal.heartbeat").increment();

                    String scrape = registry.scrape();
                    assertThat(scrape)
                            .contains("application=\"pay-service\"")
                            .contains("zone=\"cn-north\"")
                            .contains("orderId=\"ORD-20261116\"")
                            .doesNotContain("orderId=\"ORD-2\"")
                            .doesNotContain("internal_heartbeat");
                });
    }
}
