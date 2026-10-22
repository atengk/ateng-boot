package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.observability.security.ActuatorEndpointSecurityFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengSecurityAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengSecurityAutoConfiguration 自动装配单元测试")
class AtengSecurityAutoConfigurationTest {

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengObservabilityAutoConfiguration.class,
                    AtengSecurityAutoConfiguration.class
            ));

    private final ApplicationContextRunner nonWebContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengObservabilityAutoConfiguration.class,
                    AtengSecurityAutoConfiguration.class
            ));

    @Test
    @DisplayName("Servlet Web 环境且默认配置下，自动装配 ActuatorEndpointSecurityFilter")
    void testServletWebAutoConfiguration() {
        webContextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengSecurityAutoConfiguration.class);
            assertThat(context).hasSingleBean(ActuatorEndpointSecurityFilter.class);
        });
    }

    @Test
    @DisplayName("当 lockdown-enabled=false 时，跳过安全过滤器装配")
    void testLockdownDisabled() {
        webContextRunner
                .withPropertyValues("ateng.boot.observability.security.lockdown-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(AtengSecurityAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(ActuatorEndpointSecurityFilter.class);
                });
    }

    @Test
    @DisplayName("当全局开关 ateng.boot.observability.enabled=false 时，整体跳过安全装配")
    void testGlobalDisabled() {
        webContextRunner
                .withPropertyValues("ateng.boot.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengSecurityAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(ActuatorEndpointSecurityFilter.class);
                });
    }

    @Test
    @DisplayName("在非 Web 环境（如纯消费 Worker）下，自动跳过 Web 安全过滤器装配")
    void testNonWebEnvironment() {
        nonWebContextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AtengSecurityAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(ActuatorEndpointSecurityFilter.class);
        });
    }
}
