package io.github.atengk.boot.realtime.autoconfigure;

import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.websocket.AtengWebSocketConfigurer;
import io.github.atengk.boot.realtime.websocket.AtengWebSocketHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 实时通信底座主自动装配类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@AutoConfiguration
@EnableConfigurationProperties(RealtimeProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.realtime", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengRealtimeAutoConfiguration {

    /**
     * WebSocket 传输引擎自动装配
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebSocket
    @ConditionalOnClass(WebSocketConfigurer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.realtime.websocket", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class AtengWebSocketConfiguration {

        /**
         * 注册核心原生 WebSocket 处理器
         */
        @Bean
        @ConditionalOnMissingBean(AtengWebSocketHandler.class)
        public AtengWebSocketHandler atengWebSocketHandler() {
            return new AtengWebSocketHandler();
        }

        /**
         * 注册 WebSocket 端点配置器
         */
        @Bean
        @ConditionalOnMissingBean(AtengWebSocketConfigurer.class)
        public AtengWebSocketConfigurer atengWebSocketConfigurer(RealtimeProperties properties,
                                                                 AtengWebSocketHandler webSocketHandler,
                                                                 ObjectProvider<HandshakeInterceptor> handshakeInterceptors) {
            return new AtengWebSocketConfigurer(properties, webSocketHandler, handshakeInterceptors);
        }

        /**
         * 自动调优底座容器缓冲区（64KB 文本/二进制缓冲区，10s 异步发送超时）
         */
        @Bean
        @ConditionalOnMissingBean(ServletServerContainerFactoryBean.class)
        @ConditionalOnClass(ServletServerContainerFactoryBean.class)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public ServletServerContainerFactoryBean createWebSocketContainer(RealtimeProperties properties) {
            ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
            container.setMaxTextMessageBufferSize((int) properties.getWebsocket().getMaxTextMessageBufferSize().toBytes());
            container.setMaxBinaryMessageBufferSize((int) properties.getWebsocket().getMaxBinaryMessageBufferSize().toBytes());
            container.setAsyncSendTimeout(properties.getWebsocket().getAsyncSendTimeout().toMillis());
            container.setMaxSessionIdleTimeout(properties.getHeartbeat().getIdleTimeout().toMillis());
            return container;
        }
    }
}
