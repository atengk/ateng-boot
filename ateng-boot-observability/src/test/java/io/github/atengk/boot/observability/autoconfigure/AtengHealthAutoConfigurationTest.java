package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.observability.health.AiSafeHealthIndicator;
import io.github.atengk.boot.observability.health.DynamicDatasourceHealthIndicator;
import io.github.atengk.boot.observability.health.KafkaSafeHealthIndicator;
import io.github.atengk.boot.observability.health.MailSafeHealthIndicator;
import io.github.atengk.boot.observability.health.RabbitmqSafeHealthIndicator;
import io.github.atengk.boot.observability.health.RealtimeHealthIndicator;
import io.github.atengk.boot.observability.health.RedisSafeHealthIndicator;
import io.github.atengk.boot.observability.health.RocketmqHealthIndicator;
import io.github.atengk.boot.observability.health.SearchSafeHealthIndicator;
import io.github.atengk.boot.observability.health.StorageHealthIndicator;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.storage.autoconfigure.AtengStorageAutoConfiguration;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
            assertThat(context).hasSingleBean(MailSafeHealthIndicator.class);
            assertThat(context).hasBean("mailHealthIndicator");
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
                        "ateng.boot.observability.health.search.enabled=false",
                        "ateng.boot.observability.health.mail.enabled=false"
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
                    assertThat(context).doesNotHaveBean(MailSafeHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("缺席 ateng-boot-mail 类路径时可观测健康自动装配可独立无错启动")
    void testMailHealthIndicatorSkippedWhenMailAbsent() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(MailAccountRegistry.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengHealthAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean("mailHealthIndicator");
                    assertThat(context).hasSingleBean(StorageHealthIndicator.class);
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

    @Test
    @DisplayName("默认装配 AiSafeHealthIndicator，并在缺少 AiModelRegistry 或关闭开关时跳过装配")
    void testAiHealthIndicatorAutoConfiguredAndFiltered() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(AiSafeHealthIndicator.class));

        contextRunner
                .withPropertyValues("ateng.boot.observability.health.ai.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AiSafeHealthIndicator.class));

        contextRunner
                .withClassLoader(new FilteredClassLoader(AiModelRegistry.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengHealthAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AiSafeHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("AtengHealthAutoConfiguration 显式声明排在 AtengStorageAutoConfiguration 之前")
    void testAutoConfigureBeforeIncludesStorageAutoConfiguration() {
        AutoConfigureBefore annotation = AtengHealthAutoConfiguration.class.getAnnotation(AutoConfigureBefore.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).contains(AtengStorageAutoConfiguration.class.getName());
    }

    @Test
    @DisplayName("与 AtengStorageAutoConfiguration 及 AtengAsyncAutoConfiguration 任意声明顺序共存时，始终优先装配防假死 StorageHealthIndicator")
    void testCoexistenceOrderPrefersObservabilityStorageHealthIndicator(@TempDir Path tempDir) {
        List<AutoConfigurations> orders = List.of(
                AutoConfigurations.of(
                        AtengStorageAutoConfiguration.class,
                        AtengAsyncAutoConfiguration.class,
                        AtengObservabilityAutoConfiguration.class,
                        AtengHealthAutoConfiguration.class
                ),
                AutoConfigurations.of(
                        AtengHealthAutoConfiguration.class,
                        AtengObservabilityAutoConfiguration.class,
                        AtengAsyncAutoConfiguration.class,
                        AtengStorageAutoConfiguration.class
                )
        );

        for (AutoConfigurations autoConfigurations : orders) {
            new ApplicationContextRunner()
                    .withConfiguration(autoConfigurations)
                    .withPropertyValues(
                            "ateng.boot.storage.type=local",
                            "ateng.boot.storage.local.base-path=" + tempDir
                    )
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(StorageHealthIndicator.class);
                        assertThat(context).doesNotHaveBean(io.github.atengk.boot.storage.health.StorageHealthIndicator.class);
                        assertThat(context.getBean("storageHealthIndicator")).isInstanceOf(StorageHealthIndicator.class);

                        Map<String, HealthIndicator> allIndicators = context.getBeansOfType(HealthIndicator.class);
                        long storageProbeCount = allIndicators.values().stream()
                                .filter(bean -> bean instanceof StorageHealthIndicator
                                        || bean instanceof io.github.atengk.boot.storage.health.StorageHealthIndicator)
                                .count();
                        assertThat(storageProbeCount).isEqualTo(1L);
                    });
        }
    }

    @Test
    @DisplayName("关闭 observability 存储探针时回退基础版；同时关闭 management.health.storage 时两者均不装配")
    void testFallbackAndBothDisabledWithStorageAutoConfiguration(@TempDir Path tempDir) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengStorageAutoConfiguration.class,
                        AtengAsyncAutoConfiguration.class,
                        AtengObservabilityAutoConfiguration.class,
                        AtengHealthAutoConfiguration.class
                ))
                .withPropertyValues(
                        "ateng.boot.storage.type=local",
                        "ateng.boot.storage.local.base-path=" + tempDir,
                        "ateng.boot.observability.health.storage.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(StorageHealthIndicator.class);
                    assertThat(context).hasSingleBean(io.github.atengk.boot.storage.health.StorageHealthIndicator.class);
                    assertThat(context.getBean("storageHealthIndicator"))
                            .isInstanceOf(io.github.atengk.boot.storage.health.StorageHealthIndicator.class);
                });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengStorageAutoConfiguration.class,
                        AtengAsyncAutoConfiguration.class,
                        AtengObservabilityAutoConfiguration.class,
                        AtengHealthAutoConfiguration.class
                ))
                .withPropertyValues(
                        "ateng.boot.storage.type=local",
                        "ateng.boot.storage.local.base-path=" + tempDir,
                        "ateng.boot.observability.health.storage.enabled=false",
                        "management.health.storage.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(StorageHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.storage.health.StorageHealthIndicator.class);
                    assertThat(context).doesNotHaveBean("storageHealthIndicator");
                });
    }

    @Test
    @DisplayName("自定义 Bean 名的防假死 StorageHealthIndicator 存在且关闭内置探针时，AtengStorageAutoConfiguration 仍应让位")
    void testStorageAutoConfigurationYieldsWhenCustomBeanNameSafeProbeRegistered(@TempDir Path tempDir) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengStorageAutoConfiguration.class,
                        AtengAsyncAutoConfiguration.class,
                        AtengObservabilityAutoConfiguration.class,
                        AtengHealthAutoConfiguration.class
                ))
                .withUserConfiguration(CustomSafeStorageProbeConfig.class)
                .withPropertyValues(
                        "ateng.boot.storage.type=local",
                        "ateng.boot.storage.local.base-path=" + tempDir,
                        "ateng.boot.observability.health.storage.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("customSafeStorageProbe");
                    assertThat(context).hasSingleBean(StorageHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.storage.health.StorageHealthIndicator.class);
                    assertThat(context).doesNotHaveBean("storageHealthIndicator");
                });
    }

    @Configuration
    static class CustomSafeStorageProbeConfig {
        @Bean
        public StorageHealthIndicator customSafeStorageProbe(
                ObjectProvider<StorageDriverFactory> driverFactoryProvider,
                ObjectProvider<StorageDriver> defaultDriverProvider,
                ObservabilityProperties properties) {
            return new StorageHealthIndicator(driverFactoryProvider, defaultDriverProvider, properties);
        }
    }
}
