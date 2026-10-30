package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.health.DynamicDatasourceHealthIndicator;
import io.github.atengk.boot.observability.health.KafkaSafeHealthIndicator;
import io.github.atengk.boot.observability.health.RabbitmqSafeHealthIndicator;
import io.github.atengk.boot.observability.health.RealtimeHealthIndicator;
import io.github.atengk.boot.observability.health.RedisSafeHealthIndicator;
import io.github.atengk.boot.observability.health.RocketmqHealthIndicator;
import io.github.atengk.boot.observability.health.SearchSafeHealthIndicator;
import io.github.atengk.boot.observability.health.StorageHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengHealthAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengHealthAutoConfiguration 自动装配单元测试")
class AtengHealthAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengObservabilityAutoConfiguration.class,
                    AtengHealthAutoConfiguration.class
            ));

    @Test
    @DisplayName("默认情况下自动装配线程池与八大 Starter 专属健康探针 Bean")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengHealthAutoConfiguration.class);
            assertThat(context).hasBean("atengHealthProbeExecutor");
            assertThat(context.getBean("atengHealthProbeExecutor")).isInstanceOf(ExecutorService.class);
            assertThat(context).hasSingleBean(StorageHealthIndicator.class);
            assertThat(context).hasSingleBean(RocketmqHealthIndicator.class);
            assertThat(context).hasSingleBean(RealtimeHealthIndicator.class);
            assertThat(context).hasSingleBean(DynamicDatasourceHealthIndicator.class);
            assertThat(context).hasSingleBean(RedisSafeHealthIndicator.class);
            assertThat(context).hasSingleBean(KafkaSafeHealthIndicator.class);
            assertThat(context).hasSingleBean(RabbitmqSafeHealthIndicator.class);
            assertThat(context).hasSingleBean(SearchSafeHealthIndicator.class);
        });
    }

    @Test
    @DisplayName("当细粒度开关为 false 时，分别跳过对应探针装配")
    void testIndividualHealthProbesDisabled() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.observability.health.storage.enabled=false",
                        "ateng.boot.observability.health.rocketmq.enabled=false",
                        "ateng.boot.observability.health.realtime.enabled=false",
                        "ateng.boot.observability.health.dynamic-datasource.enabled=false",
                        "ateng.boot.observability.health.redis.enabled=false",
                        "ateng.boot.observability.health.kafka.enabled=false",
                        "ateng.boot.observability.health.rabbitmq.enabled=false",
                        "ateng.boot.observability.health.search.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengHealthAutoConfiguration.class);
                    assertThat(context).hasBean("atengHealthProbeExecutor");
                    assertThat(context).doesNotHaveBean(StorageHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(RocketmqHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(RealtimeHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(DynamicDatasourceHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(RedisSafeHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(KafkaSafeHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(RabbitmqSafeHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(SearchSafeHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("当全局开关 ateng.boot.observability.enabled=false 时，整体跳过健康装配")
    void testGlobalDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengHealthAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean("atengHealthProbeExecutor");
                    assertThat(context).doesNotHaveBean(StorageHealthIndicator.class);
                });
    }
}
