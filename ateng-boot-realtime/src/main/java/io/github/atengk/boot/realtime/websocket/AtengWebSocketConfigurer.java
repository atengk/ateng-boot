package io.github.atengk.boot.realtime.websocket;

import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;

/**
 * 实时通信原生 WebSocket 端点注册器
 * <p>
 * 遵循 Spring Boot 规范注册端点路径、配置跨域放行白名单，并自动装配并发写安全装饰器。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@RequiredArgsConstructor
public class AtengWebSocketConfigurer implements WebSocketConfigurer {

    private final RealtimeProperties properties;
    private final WebSocketHandler webSocketHandler;
    private final ObjectProvider<HandshakeInterceptor> handshakeInterceptors;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (!properties.getWebsocket().isEnabled()) {
            log.info("[Realtime] WebSocket 传输引擎已按配置禁用");
            return;
        }

        String path = properties.getWebsocket().getPath();
        log.info("[Realtime] 注册 WebSocket 核心端点: path={}", path);

        // 统一包装为并发安全处理器
        WebSocketHandler concurrentHandler = new ConcurrentWebSocketHandlerDecorator(
                webSocketHandler,
                (int) properties.getWebsocket().getSendTimeout().toMillis(),
                (int) properties.getWebsocket().getSendBufferLimit().toBytes()
        );

        WebSocketHandlerRegistration registration = registry.addHandler(concurrentHandler, path);

        // 跨域来源模式配置（优先 setAllowedOriginPatterns 规避携带 Cookie 时的通配限制）
        List<String> allowedOrigins = properties.getCors().getAllowedOrigins();
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            registration.setAllowedOriginPatterns(allowedOrigins.toArray(new String[0]));
        } else {
            registration.setAllowedOriginPatterns("*");
        }

        // 注入握手拦截器（若已配置或后续装配）
        handshakeInterceptors.orderedStream().forEach(registration::addInterceptors);
    }
}
