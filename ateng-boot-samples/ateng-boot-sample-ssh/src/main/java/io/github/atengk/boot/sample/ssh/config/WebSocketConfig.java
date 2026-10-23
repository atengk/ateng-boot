package io.github.atengk.boot.sample.ssh.config;

import io.github.atengk.boot.sample.ssh.websocket.WebSshWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSSH WebSocket 路由注册配置
 * <p>
 * 将 {@link WebSshWebSocketHandler} 挂载至 {@code /ws/ssh} 端点，支持全跨域开放。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSshWebSocketHandler webSshWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSshWebSocketHandler, "/ws/ssh")
                .setAllowedOrigins("*");
    }
}
