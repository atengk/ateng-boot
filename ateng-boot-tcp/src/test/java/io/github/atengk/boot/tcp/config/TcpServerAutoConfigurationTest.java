package io.github.atengk.boot.tcp.config;

import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.server.TcpServer;
import io.github.atengk.boot.tcp.session.TcpSessionManager;
import io.github.atengk.boot.tcp.template.TcpServerTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

            // 验证单次停机重启不销毁共享 TcpRequestCorrelationManager，仅在容器销毁时触发 destroy()
            TcpRequestCorrelationManager correlationManager = context.getBean(TcpRequestCorrelationManager.class);
            tcpServer.stop();
            tcpServer.start();
            assertThat(tcpServer.isRunning()).isTrue();

            CompletableFuture<TcpPacket> future = correlationManager.register("REQ-REBOOT", Duration.ofSeconds(2));
            assertThat(correlationManager.complete("REQ-REBOOT",
                    TcpPacket.of("ACK", "REQ-REBOOT", TcpPayload.of("OK")))).isTrue();
            assertThat(future.get(1, TimeUnit.SECONDS).getPayload().asString()).isEqualTo("OK");

            CompletableFuture<TcpPacket> pendingOnClose = correlationManager.register("REQ-ON-CLOSE", Duration.ofSeconds(10));
            context.close();
            assertThat(pendingOnClose.isCompletedExceptionally()).isTrue();
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
