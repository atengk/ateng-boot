package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.template.TcpClientTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * TCP 客户端自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 客户端自动装配单元测试")
class TcpClientAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengTcpAutoConfiguration.class,
                    TcpClientAutoConfiguration.class
            ));

    @Test
    @DisplayName("默认情况下客户端装配不生效（ateng.boot.tcp.client.enabled 默认为 false）")
    void testDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(TcpClientAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(TcpClient.class);
            assertThat(context).doesNotHaveBean(TcpClientTemplate.class);
        });
    }

    @Test
    @DisplayName("配置 ateng.boot.tcp.client.enabled=true 时自动装配生效")
    void testEnabledWhenPropertyConfigured() {
        contextRunner.withPropertyValues(
                "ateng.boot.tcp.client.enabled=true",
                "ateng.boot.tcp.client.host=127.0.0.1",
                "ateng.boot.tcp.client.port=18088"
        ).run(context -> {
            assertThat(context).hasSingleBean(TcpClientAutoConfiguration.class);
            assertThat(context).hasSingleBean(TcpRequestCorrelationManager.class);
            assertThat(context).hasSingleBean(TcpClient.class);
            assertThat(context).hasSingleBean(TcpClientTemplate.class);
        });
    }

    @Test
    @DisplayName("验证自定义 Bean 缺省后退机制（@ConditionalOnMissingBean）")
    void testCustomBeanTakeover() {
        contextRunner.withUserConfiguration(CustomClientTemplateConfig.class)
                .withPropertyValues(
                        "ateng.boot.tcp.client.enabled=true",
                        "ateng.boot.tcp.client.port=18088"
                ).run(context -> {
                    assertThat(context).hasSingleBean(TcpClientTemplate.class);
                    TcpClientTemplate template = context.getBean(TcpClientTemplate.class);
                    assertThat(template).isSameAs(CustomClientTemplateConfig.customTemplate);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomClientTemplateConfig {
        static final TcpClientTemplate customTemplate = mock(TcpClientTemplate.class);

        @Bean
        public TcpClientTemplate tcpClientTemplate() {
            return customTemplate;
        }
    }
}
