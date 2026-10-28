package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.realtime.autoconfigure.AtengRealtimeAutoConfiguration;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import jakarta.websocket.server.ServerContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WebSocket 配置器与端点注册单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class AtengWebSocketConfigurerTest {

    private final ServerContainer mockServerContainer = mock(ServerContainer.class);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(context -> context.getServletContext().setAttribute("jakarta.websocket.server.ServerContainer", mockServerContainer))
            .withConfiguration(AutoConfigurations.of(AtengRealtimeAutoConfiguration.class));

    @Test
    void testAutoConfigurationRegistersWebSocketBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AtengWebSocketHandler.class);
            assertThat(context).hasSingleBean(AtengWebSocketConfigurer.class);
            assertThat(context).hasSingleBean(ServletServerContainerFactoryBean.class);

            ServletServerContainerFactoryBean container = context.getBean(ServletServerContainerFactoryBean.class);
            assertThat(container.getMaxTextMessageBufferSize()).isEqualTo(65536);
            assertThat(container.getMaxBinaryMessageBufferSize()).isEqualTo(65536);
            assertThat(container.getAsyncSendTimeout()).isEqualTo(10000L);

            // 验证已将 64KB 缓冲区注入容器
            verify(mockServerContainer).setDefaultMaxTextMessageBufferSize(65536);
            verify(mockServerContainer).setDefaultMaxBinaryMessageBufferSize(65536);
            verify(mockServerContainer).setAsyncSendTimeout(10000L);
        });
    }

    @Test
    void testWebSocketDisabledConfiguration() {
        runner.withPropertyValues("ateng.boot.realtime.websocket.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AtengWebSocketHandler.class);
                    assertThat(context).doesNotHaveBean(AtengWebSocketConfigurer.class);
                    assertThat(context).doesNotHaveBean(ServletServerContainerFactoryBean.class);
                });
    }

    @Test
    void testRegisterWebSocketHandlersExecution() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getWebsocket().setPath("/ws/custom");
        AtengWebSocketHandler handler = new AtengWebSocketHandler();
        AtengWebSocketConfigurer configurer = new AtengWebSocketConfigurer(
                properties,
                handler,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.web.socket.server.HandshakeInterceptor.class)
        );

        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(any(), eq("/ws/custom"))).thenReturn(registration);

        configurer.registerWebSocketHandlers(registry);

        verify(registry, times(1)).addHandler(any(ConcurrentWebSocketHandlerDecorator.class), eq("/ws/custom"));
        verify(registration, times(1)).setAllowedOriginPatterns(eq("*"));
    }
}
