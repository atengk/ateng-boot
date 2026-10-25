package io.github.atengk.boot.job.local.config;

import io.github.atengk.boot.job.local.actuator.LocalJobEndpoint;
import io.github.atengk.boot.job.local.alert.JobAlertHandler;
import io.github.atengk.boot.job.local.alert.LocalJobAlertListener;
import io.github.atengk.boot.job.local.lifecycle.LocalJobLifecycleManager;
import io.github.atengk.boot.job.local.lock.JobClusterLockProvider;
import io.github.atengk.boot.job.local.processor.LocalJobAnnotationBeanPostProcessor;
import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import io.github.atengk.boot.job.local.storage.JobLogStorageProvider;
import io.github.atengk.boot.job.local.storage.JobMetadataRepository;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AtengJobLocalAutoConfiguration} 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("AtengJobLocalAutoConfiguration 自动装配测试")
class AtengJobLocalAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengJobLocalAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功自动装配核心调度组件、存储 SPI 与生命周期管理器")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JobLocalProperties.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            assertThat(context).hasSingleBean(JobMetadataRepository.class);
            assertThat(context).hasSingleBean(JobLogStorageProvider.class);
            assertThat(context).hasSingleBean(JobClusterLockProvider.class);
            assertThat(context).hasSingleBean(LocalJobRegistry.class);
            assertThat(context).hasSingleBean(LocalJobLifecycleManager.class);
            assertThat(context).hasSingleBean(LocalJobTemplate.class);
            assertThat(context).hasSingleBean(LocalJobAnnotationBeanPostProcessor.class);
            assertThat(context).hasSingleBean(LocalJobEndpoint.class);
            assertThat(context).doesNotHaveBean(LocalJobAlertListener.class);

            JobLocalProperties properties = context.getBean(JobLocalProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getThreadNamePrefix()).isEqualTo("ateng-job-local-");
            assertThat(properties.isPreferDatabaseConfig()).isTrue();
            assertThat(properties.getInMemoryLogCapacity()).isEqualTo(100);

            LocalJobLifecycleManager lifecycleManager = context.getBean(LocalJobLifecycleManager.class);
            assertThat(lifecycleManager.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
            assertThat(lifecycleManager.isAutoStartup()).isTrue();
        });
    }

    @Test
    @DisplayName("当 ateng.boot.job.local.enabled=false 时守卫生效，不装配任何组件")
    void testDisabledAutoConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.job.local.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ThreadPoolTaskScheduler.class);
                    assertThat(context).doesNotHaveBean(ThreadPoolTaskExecutor.class);
                    assertThat(context).doesNotHaveBean(JobMetadataRepository.class);
                    assertThat(context).doesNotHaveBean(JobLogStorageProvider.class);
                    assertThat(context).doesNotHaveBean(JobClusterLockProvider.class);
                    assertThat(context).doesNotHaveBean(LocalJobRegistry.class);
                    assertThat(context).doesNotHaveBean(LocalJobLifecycleManager.class);
                    assertThat(context).doesNotHaveBean(LocalJobTemplate.class);
                    assertThat(context).doesNotHaveBean(LocalJobAnnotationBeanPostProcessor.class);
                    assertThat(context).doesNotHaveBean(LocalJobEndpoint.class);
                    assertThat(context).doesNotHaveBean(LocalJobAlertListener.class);
                });
    }

    @Test
    @DisplayName("当 ateng.boot.job.local.alert.enabled=true 时成功自动装配告警处理器与监听器")
    void testAlertAutoConfigurationEnabled() {
        contextRunner.withPropertyValues("ateng.boot.job.local.alert.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(JobAlertHandler.class);
                    assertThat(context).hasSingleBean(LocalJobAlertListener.class);
                });
    }

    @Test
    @DisplayName("当 ateng.boot.job.local.actuator.enabled=false 时不装配 LocalJobEndpoint")
    void testActuatorEndpointDisabled() {
        contextRunner.withPropertyValues("ateng.boot.job.local.actuator.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LocalJobEndpoint.class);
                });
    }

    @Test
    @DisplayName("支持自定义属性注入并绑定至调度线程池")
    void testCustomPropertiesBinding() {
        contextRunner.withPropertyValues(
                "ateng.boot.job.local.pool-size=12",
                "ateng.boot.job.local.thread-name-prefix=custom-job-",
                "ateng.boot.job.local.shutdown-await-termination-period=45s",
                "ateng.boot.job.local.in-memory-log-capacity=200",
                "ateng.boot.job.local.log-retention-days=14",
                "ateng.boot.job.local.alert.enabled=true"
        ).run(context -> {
            JobLocalProperties properties = context.getBean(JobLocalProperties.class);
            assertThat(properties.getPoolSize()).isEqualTo(12);
            assertThat(properties.getThreadNamePrefix()).isEqualTo("custom-job-");
            assertThat(properties.getShutdownAwaitTerminationPeriod().toSeconds()).isEqualTo(45);
            assertThat(properties.getInMemoryLogCapacity()).isEqualTo(200);
            assertThat(properties.getLogRetentionDays()).isEqualTo(14);
            assertThat(properties.getAlert().isEnabled()).isTrue();
        });
    }
}
