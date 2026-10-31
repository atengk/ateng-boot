package io.github.atengk.boot.job.snail.autoconfigure;

import com.aizuda.snailjob.client.common.config.SnailJobProperties;
import io.github.atengk.boot.job.snail.autoconfigure.AtengSnailJobAutoConfiguration.SnailJobPropertiesBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SnailJob 自动装配与属性绑定单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengSnailJobAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengSnailJobAutoConfiguration.class));

    @Test
    @DisplayName("测试默认配置下自动装配激活且属性具备生产合理默认值")
    void testDefaultAutoConfigurationEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengSnailJobAutoConfiguration.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.properties.SnailJobProperties.class);
            assertThat(context).hasSingleBean(SnailJobPropertiesBridge.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.template.SnailShardingBatchTemplate.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.template.TenantJobTemplate.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.log.SnailJobLogbackAppenderRegistrar.class);

            io.github.atengk.boot.job.snail.properties.SnailJobProperties props =
                    context.getBean(io.github.atengk.boot.job.snail.properties.SnailJobProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getServer()).isEqualTo("127.0.0.1:17888");
            assertThat(props.getNamespace()).isEqualTo("default");
            assertThat(props.getPort()).isEqualTo(17889);
            assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.getDrainTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.getDefaultJobTimeout()).isEqualTo(Duration.ofHours(1));
            assertThat(props.isLogMirrorEnabled()).isTrue();
            assertThat(props.isManagedPoolEnabled()).isTrue();

            assertThat(props.getJob().isEnabled()).isTrue();
            assertThat(props.getJob().isContextAspectEnabled()).isTrue();

            assertThat(props.getRetry().isEnabled()).isTrue();
            assertThat(props.getRetry().isContextAspectEnabled()).isTrue();

            assertThat(props.getOpenapi().isEnabled()).isTrue();
            assertThat(props.getOpenapi().getPort()).isEqualTo(8080);
            assertThat(props.getOpenapi().getPrefix()).isEqualTo("snail-job");

            assertThat(props.getAlert().isEnabled()).isTrue();
            assertThat(props.getAlert().getSuppressDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(props.getAlert().getSuppressThreshold()).isEqualTo(3);

            SnailJobPropertiesBridge bridge = context.getBean(SnailJobPropertiesBridge.class);
            assertThat(bridge.isServerConfigured()).isTrue();
        });
    }

    @Test
    @DisplayName("测试显式关闭 enabled 时自动装配平滑旁路退出")
    void testAutoConfigurationDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.job.snail.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengSnailJobAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.job.snail.properties.SnailJobProperties.class);
                    assertThat(context).doesNotHaveBean(SnailJobPropertiesBridge.class);
                });
    }

    @Test
    @DisplayName("测试强类型配置属性绑定 (Duration、子模块配置)")
    void testPropertyBinding() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.job.snail.server=192.168.1.100:18888",
                        "ateng.boot.job.snail.group=order-service",
                        "ateng.boot.job.snail.namespace=prod",
                        "ateng.boot.job.snail.token=secret-token-123",
                        "ateng.boot.job.snail.port=27889",
                        "ateng.boot.job.snail.timeout=15s",
                        "ateng.boot.job.snail.drain-timeout=45s",
                        "ateng.boot.job.snail.default-job-timeout=30m",
                        "ateng.boot.job.snail.job.enabled=false",
                        "ateng.boot.job.snail.retry.context-aspect-enabled=false",
                        "ateng.boot.job.snail.openapi.port=9090",
                        "ateng.boot.job.snail.openapi.prefix=api/v1/snail",
                        "ateng.boot.job.snail.openapi.https=true",
                        "ateng.boot.job.snail.alert.suppress-threshold=5",
                        "ateng.boot.job.snail.alert.suppress-duration=10m"
                )
                .run(context -> {
                    io.github.atengk.boot.job.snail.properties.SnailJobProperties props =
                            context.getBean(io.github.atengk.boot.job.snail.properties.SnailJobProperties.class);

                    assertThat(props.getServer()).isEqualTo("192.168.1.100:18888");
                    assertThat(props.getGroup()).isEqualTo("order-service");
                    assertThat(props.getNamespace()).isEqualTo("prod");
                    assertThat(props.getToken()).isEqualTo("secret-token-123");
                    assertThat(props.getPort()).isEqualTo(27889);
                    assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(15));
                    assertThat(props.getDrainTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(props.getDefaultJobTimeout()).isEqualTo(Duration.ofMinutes(30));

                    assertThat(props.getJob().isEnabled()).isFalse();
                    assertThat(props.getRetry().isContextAspectEnabled()).isFalse();

                    assertThat(props.getOpenapi().getPort()).isEqualTo(9090);
                    assertThat(props.getOpenapi().getPrefix()).isEqualTo("api/v1/snail");
                    assertThat(props.getOpenapi().isHttps()).isTrue();

                    assertThat(props.getAlert().getSuppressThreshold()).isEqualTo(5);
                    assertThat(props.getAlert().getSuppressDuration()).isEqualTo(Duration.ofMinutes(10));
                });
    }

    @Test
    @DisplayName("测试当服务端地址为空时平滑探测旁路不崩溃")
    void testSilentProbeWhenServerBlank() {
        contextRunner
                .withPropertyValues("ateng.boot.job.snail.server=")
                .run(context -> {
                    assertThat(context).hasSingleBean(SnailJobPropertiesBridge.class);
                    SnailJobPropertiesBridge bridge = context.getBean(SnailJobPropertiesBridge.class);
                    assertThat(bridge.isServerConfigured()).isFalse();
                });
    }

    @Test
    @DisplayName("测试用户自定义 Bean 可平滑接管默认装配 (@ConditionalOnMissingBean)")
    void testMissingBeanOverride() {
        contextRunner
                .withUserConfiguration(CustomBridgeConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(SnailJobPropertiesBridge.class);
                    SnailJobPropertiesBridge bridge = context.getBean(SnailJobPropertiesBridge.class);
                    assertThat(bridge.getProperties().getServer()).isEqualTo("custom-server:9999");
                });
    }

    @Test
    @DisplayName("测试同步映射至 SnailJob 原生配置对象")
    void testSyncNativeProperties() {
        contextRunner
                .withUserConfiguration(NativePropertiesConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.job.snail.server=10.0.0.1:18888",
                        "ateng.boot.job.snail.namespace=test-namespace",
                        "ateng.boot.job.snail.group=test-group",
                        "ateng.boot.job.snail.token=token-abc",
                        "ateng.boot.job.snail.port=19999"
                )
                .run(context -> {
                    SnailJobProperties nativeProps = context.getBean(SnailJobProperties.class);
                    assertThat(nativeProps.getServer().getHost()).isEqualTo("10.0.0.1");
                    assertThat(nativeProps.getServer().getPort()).isEqualTo(18888);
                    assertThat(nativeProps.getNamespace()).isEqualTo("test-namespace");
                    assertThat(nativeProps.getGroup()).isEqualTo("test-group");
                    assertThat(nativeProps.getToken()).isEqualTo("token-abc");
                    assertThat(nativeProps.getPort()).isEqualTo(19999);
                });
    }

    @Test
    @DisplayName("测试显式关闭 openapi 与 log-mirror 时相关 Bean 不被装配")
    void testOpenApiAndLogMirrorDisabled() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.job.snail.openapi.enabled=false",
                        "ateng.boot.job.snail.log-mirror-enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate.class);
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.job.snail.log.SnailJobLogbackAppenderRegistrar.class);
                });
    }

    @Test
    @DisplayName("测试自定义 SnailJobAdminTemplate 可平滑接管默认装配 (@ConditionalOnMissingBean)")
    void testCustomAdminTemplateOverride() {
        contextRunner
                .withUserConfiguration(CustomAdminTemplateConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate.class);
                    io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate adminTemplate =
                            context.getBean(io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate.class);
                    assertThat(adminTemplate).isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAdminTemplateConfiguration {
        @Bean
        public io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate customAdminTemplate() {
            return new io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBridgeConfiguration {
        @Bean
        public SnailJobPropertiesBridge customBridge() {
            io.github.atengk.boot.job.snail.properties.SnailJobProperties props =
                    new io.github.atengk.boot.job.snail.properties.SnailJobProperties();
            props.setServer("custom-server:9999");
            return new SnailJobPropertiesBridge(props, true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NativePropertiesConfiguration {
        @Bean
        public SnailJobProperties nativeProperties() {
            return new SnailJobProperties();
        }
    }
}
