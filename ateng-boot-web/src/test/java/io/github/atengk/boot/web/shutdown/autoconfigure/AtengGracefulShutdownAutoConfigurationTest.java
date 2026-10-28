package io.github.atengk.boot.web.shutdown.autoconfigure;

import io.github.atengk.boot.web.autoconfigure.AtengWebAutoConfiguration;
import io.github.atengk.boot.web.shutdown.AtengGracefulShutdownHandler;
import io.github.atengk.boot.web.shutdown.PreStopController;
import io.github.atengk.boot.web.shutdown.PreStopEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 优雅停机与流量排空自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("AtengGracefulShutdownAutoConfiguration 单元测试")
class AtengGracefulShutdownAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengWebAutoConfiguration.class,
                    AtengGracefulShutdownAutoConfiguration.class
            ))
            .withPropertyValues("ateng.boot.web.shutdown.drain-timeout=10ms");

    @Test
    @DisplayName("默认装配：类路径存在 Actuator 时注册 PreStopEndpoint，不注册 PreStopController")
    void testDefaultAutoconfigurationWithActuator() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengGracefulShutdownHandler.class);
            assertThat(context).hasSingleBean(PreStopEndpoint.class);
            assertThat(context).doesNotHaveBean(PreStopController.class);
        });
    }

    @Test
    @DisplayName("降级装配：当过滤掉 Actuator Endpoint 类时，自动降级注册 PreStopController")
    void testFallbackToMvcControllerWithoutActuator() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(Endpoint.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengGracefulShutdownHandler.class);
                    assertThat(context).doesNotHaveBean(PreStopEndpoint.class);
                    assertThat(context).hasSingleBean(PreStopController.class);
                });
    }

    @Test
    @DisplayName("禁用装配：ateng.boot.web.shutdown.enabled=false 时不装配任何停机组件")
    void testDisabledConfiguration() {
        contextRunner
                .withPropertyValues("ateng.boot.web.shutdown.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengGracefulShutdownHandler.class);
                    assertThat(context).doesNotHaveBean(PreStopEndpoint.class);
                    assertThat(context).doesNotHaveBean(PreStopController.class);
                });
    }

    @Test
    @DisplayName("显式启用 MVC：配置 mvc-endpoint-enabled=true 时注册 PreStopController")
    void testExplicitMvcEndpointEnabled() {
        contextRunner
                .withPropertyValues("ateng.boot.web.shutdown.mvc-endpoint-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengGracefulShutdownHandler.class);
                    assertThat(context).hasSingleBean(PreStopEndpoint.class);
                    assertThat(context).hasSingleBean(PreStopController.class);
                });
    }

    @Test
    @DisplayName("禁用 Actuator 端点：actuator-endpoint-enabled=false 时降级装配 PreStopController")
    void testActuatorEndpointDisabledFallback() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.web.shutdown.actuator-endpoint-enabled=false",
                        "ateng.boot.web.shutdown.mvc-endpoint-enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengGracefulShutdownHandler.class);
                    assertThat(context).doesNotHaveBean(PreStopEndpoint.class);
                    assertThat(context).hasSingleBean(PreStopController.class);
                });
    }
}
