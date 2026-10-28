package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.properties.AtengTcpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 自动装配单元测试")
class AtengTcpAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengTcpAutoConfiguration.class));

    @Test
    @DisplayName("默认情况下自动装配生效并加载配置属性 Bean")
    void testAutoConfigurationEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtengTcpAutoConfiguration.class);
            assertThat(context).hasSingleBean(AtengTcpProperties.class);
            AtengTcpProperties properties = context.getBean(AtengTcpProperties.class);
            assertThat(properties.isEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("显式配置 ateng.boot.tcp.enabled=false 时自动装配后退")
    void testAutoConfigurationDisabledWhenPropertyFalse() {
        contextRunner.withPropertyValues("ateng.boot.tcp.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengTcpAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(AtengTcpProperties.class);
                });
    }

    @Test
    @DisplayName("验证自定义配置属性成功注入容器上下文")
    void testCustomPropertiesBindingInContext() {
        contextRunner.withPropertyValues(
                "ateng.boot.tcp.server.enabled=true",
                "ateng.boot.tcp.server.port=9999",
                "ateng.boot.tcp.client.enabled=true",
                "ateng.boot.tcp.client.host=192.168.1.50"
        ).run(context -> {
            assertThat(context).hasSingleBean(AtengTcpProperties.class);
            AtengTcpProperties properties = context.getBean(AtengTcpProperties.class);
            assertThat(properties.getServer().isEnabled()).isTrue();
            assertThat(properties.getServer().getPort()).isEqualTo(9999);
            assertThat(properties.getClient().isEnabled()).isTrue();
            assertThat(properties.getClient().getHost()).isEqualTo("192.168.1.50");
        });
    }
}
