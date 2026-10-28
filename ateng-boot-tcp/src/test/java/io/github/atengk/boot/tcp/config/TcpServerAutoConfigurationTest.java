package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 服务端自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 服务端自动装配单元测试")
class TcpServerAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengTcpAutoConfiguration.class,
                    TcpServerAutoConfiguration.class
            ));

    @Test
    @DisplayName("默认情况下服务端装配不生效（ateng.boot.tcp.server.enabled 默认为 false）")
    void testDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(TcpServerAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(TcpSessionManager.class);
            assertThat(context).doesNotHaveBean(TcpServer.class);
            assertThat(context).doesNotHaveBean(TcpServerTemplate.class);
        });
    }

    @Test
    @DisplayName("配置 ateng.boot.tcp.server.enabled=true 时自动装配生效")
    void testEnabledWhenPropertyConfigured() {
        contextRunner.withPropertyValues(
                "ateng.boot.tcp.server.enabled=true",
                "ateng.boot.tcp.server.port=0"
        ).run(context -> {
            assertThat(context).hasSingleBean(TcpServerAutoConfiguration.class);
            assertThat(context).hasSingleBean(TcpSessionManager.class);
            assertThat(context).hasSingleBean(TcpServer.class);
            assertThat(context).hasSingleBean(TcpServerTemplate.class);

            TcpServer tcpServer = context.getBean(TcpServer.class);
            assertThat(tcpServer.isRunning()).isTrue();
            assertThat(tcpServer.getActualPort()).isGreaterThan(0);
        });
    }

    @Test
    @DisplayName("验证自定义 Bean 缺省后退机制（@ConditionalOnMissingBean）")
    void testCustomBeanOverride() {
        contextRunner.withUserConfiguration(CustomUserConfiguration.class)
                .withPropertyValues(
                        "ateng.boot.tcp.server.enabled=true",
                        "ateng.boot.tcp.server.port=0"
                ).run(context -> {
                    assertThat(context).hasSingleBean(TcpSessionManager.class);
                    CustomTcpSessionManager customManager = context.getBean(CustomTcpSessionManager.class);
                    assertThat(customManager).isNotNull();

                    TcpServerTemplate template = context.getBean(TcpServerTemplate.class);
                    assertThat(template.getSessionManager()).isSameAs(customManager);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomUserConfiguration {
        @Bean
        public TcpSessionManager tcpSessionManager() {
            return new CustomTcpSessionManager();
        }
    }

    static class CustomTcpSessionManager extends TcpSessionManager {
    }
}
