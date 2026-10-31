package io.github.atengk.boot.job.xxl.autoconfigure;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.aspect.XxlJobContextAspect;
import io.github.atengk.boot.job.xxl.executor.AtengXxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.logback.XxlJobLogbackAppenderRegistrar;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.watchdog.LocalWatchdog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XXL-Job 自动装配防御性与条件装配单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengXxlJobAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengXxlJobAutoConfiguration.class));

    @Test
    @DisplayName("测试未配置 admin-addresses 时：平滑旁路降级且不崩溃，属性具备生产默认值")
    void testDefaultBypassWhenAdminAddressesMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(XxlJobProperties.class);

            XxlJobProperties properties = context.getBean(XxlJobProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getAppname()).isEqualTo("xxl-job-executor-ateng");
            assertThat(properties.getPort()).isEqualTo(9999);
            assertThat(properties.getLogRetentionDays()).isEqualTo(30);
            assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(30));

            // 未配置 admin-addresses，执行器 Bean 应当平滑缺省不装配
            assertThat(context).doesNotHaveBean(XxlJobSpringExecutor.class);

            // 但日志镜像、看门狗与四维上下文切面在启用状态下仍正常注册
            assertThat(context).hasSingleBean(LocalWatchdog.class);
            assertThat(context).hasSingleBean(XxlJobLogbackAppenderRegistrar.class);
            assertThat(context).hasSingleBean(XxlJobContextAspect.class);
        });
    }

    @Test
    @DisplayName("测试按需关闭日志镜像与四维上下文切面配置")
    void testDisableLogMirrorAndContextAspect() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.job.xxl.log-mirror-enabled=false",
                        "ateng.boot.job.xxl.context-aspect-enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(XxlJobLogbackAppenderRegistrar.class);
                    assertThat(context).doesNotHaveBean(XxlJobContextAspect.class);
                });
    }

    @Test
    @DisplayName("测试显式配置 enabled=false 时：彻底禁用执行器装配")
    void testDisabledProperty() {
        contextRunner
                .withPropertyValues("ateng.boot.job.xxl.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(XxlJobProperties.class);
                    assertThat(context).doesNotHaveBean(XxlJobSpringExecutor.class);
                });
    }

    @Test
    @DisplayName("测试配置 admin-addresses 时：成功装配 XxlJobSpringExecutor")
    void testAutoConfiguredExecutorWhenConfigured() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.job.xxl.admin-addresses=http://127.0.0.1:8080/xxl-job-admin",
                        "ateng.boot.job.xxl.appname=order-service-executor",
                        "ateng.boot.job.xxl.access-token=test-token-123",
                        "ateng.boot.job.xxl.port=9998",
                        "ateng.boot.job.xxl.log-retention-days=15",
                        "ateng.boot.job.xxl.timeout=10s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(XxlJobSpringExecutor.class);

                    XxlJobSpringExecutor executor = context.getBean(XxlJobSpringExecutor.class);
                    assertThat(executor).isInstanceOf(AtengXxlJobSpringExecutor.class);
                    assertThat(executor.getAppname()).isEqualTo("order-service-executor");
                    assertThat(executor.getAccessToken()).isEqualTo("test-token-123");
                    assertThat(executor.getPort()).isEqualTo(9998);
                });
    }

    @Test
    @DisplayName("测试用户自定义 @Bean 时：@ConditionalOnMissingBean 生效并由用户完全接管")
    void testCustomBeanTakesPrecedence() {
        contextRunner
                .withUserConfiguration(CustomExecutorConfiguration.class)
                .withPropertyValues("ateng.boot.job.xxl.admin-addresses=http://127.0.0.1:8080/xxl-job-admin")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(XxlJobSpringExecutor.class);

                    XxlJobSpringExecutor executor = context.getBean(XxlJobSpringExecutor.class);
                    assertThat(executor.getAppname()).isEqualTo("custom-user-executor");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomExecutorConfiguration {
        @Bean
        public XxlJobSpringExecutor customXxlJobExecutor() {
            XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
            executor.setAppname("custom-user-executor");
            executor.setAdminAddresses("http://127.0.0.1:8080/custom");
            executor.setAccessToken("custom-token");
            return executor;
        }
    }

}
