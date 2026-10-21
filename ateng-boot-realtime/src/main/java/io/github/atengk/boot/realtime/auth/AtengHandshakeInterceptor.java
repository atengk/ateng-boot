package io.github.atengk.boot.realtime.auth;

import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.websocket.AtengWebSocketHandler;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 实时通信原生 WebSocket 握手安全拦截器
 * <p>
 * 遵循 Query -> Header -> Cookie 三阶优先级自适应提取用户凭据 Token，
 * 调用 RealtimeAuthHandshakeProvider SPI 进行身份解析，
 * 支持 allow-anonymous 匿名防卫放行与 Sec-WebSocket-Protocol 协议回执。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@RequiredArgsConstructor
public class AtengHandshakeInterceptor implements HandshakeInterceptor {

    private final RealtimeProperties properties;
    private final ObjectProvider<RealtimeAuthHandshakeProvider> authProvider;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) throws Exception {

        // 1. 处理并回执 Sec-WebSocket-Protocol（满足原生浏览器传递 Token 规范）
        handleSecWebSocketProtocol(request, response);

        // 2. 解析 Query 参数集合
        Map<String, String> queryParams = parseQueryParams(request.getURI());

        // 3. 提取 clientId 与 deviceType
        String clientId = queryParams.get("clientId");
        if (clientId == null || clientId.isBlank()) {
            clientId = UUID.randomUUID().toString().replace("-", "");
        } else {
            clientId = clientId.trim();
        }

        String deviceType = queryParams.get("deviceType");
        if (deviceType == null || deviceType.isBlank()) {
            deviceType = request.getHeaders().getFirst("X-Device-Type");
        }
        if (deviceType != null) {
            deviceType = deviceType.trim();
        }

        // 4. 三阶优先级自适应提取凭据 Token（Query -> Header -> Cookie）
        String token = extractToken(request, queryParams);

        // 5. 鉴权与匿名裁决
        RealtimeAuthHandshakeProvider provider = authProvider.getIfAvailable();

        if (token != null && !token.isBlank()) {
            if (provider != null) {
                RealtimeAuthPayload payload = provider.authenticate(token, attributes);
                if (payload == null) {
                    log.warn("[Realtime] WebSocket 握手鉴权失败: 无效或已过期的 Token");
                    return rejectUnauthorized(response);
                }
                // 绑定已认证身份属性
                String actualDevice = (deviceType != null && !deviceType.isBlank())
                        ? deviceType
                        : payload.getDeviceType();
                SessionKey sessionKey = SessionKey.of(payload.getUserId(), actualDevice, clientId);
                attributes.put(AtengWebSocketHandler.SESSION_KEY_ATTR, sessionKey);
                if (payload.getTenantId() != null) {
                    attributes.put(AtengWebSocketHandler.TENANT_ID_ATTR, payload.getTenantId());
                }
                if (payload.getExtraAttributes() != null) {
                    attributes.putAll(payload.getExtraAttributes());
                }
                log.debug("[Realtime] WebSocket 握手鉴权成功: sessionKey={}", sessionKey);
                return true;
            } else {
                // 未装配 AuthProvider
                if (properties.getAuth().isAllowAnonymous()) {
                    SessionKey sessionKey = SessionKey.anonymous(clientId);
                    attributes.put(AtengWebSocketHandler.SESSION_KEY_ATTR, sessionKey);
                    return true;
                }
                log.warn("[Realtime] WebSocket 未装配鉴权提供者，拒绝凭据连接");
                return rejectUnauthorized(response);
            }
        }

        // 未携带 Token：检查是否允许匿名访问
        if (properties.getAuth().isAllowAnonymous()) {
            SessionKey sessionKey = SessionKey.anonymous(clientId);
            attributes.put(AtengWebSocketHandler.SESSION_KEY_ATTR, sessionKey);
            log.debug("[Realtime] WebSocket 允许匿名访客连接: sessionKey={}", sessionKey);
            return true;
        }

        log.warn("[Realtime] WebSocket 拒绝未携带凭据的连接请求: clientId={}", clientId);
        return rejectUnauthorized(response);
    }

    /**
     * 设置 401 未授权状态并显式关闭握手响应通道
     *
     * @param response HTTP 响应
     * @return 恒定返回 false 以阻断握手
     */
    private boolean rejectUnauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        try {
            response.close();
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        if (exception != null) {
            log.warn("[Realtime] WebSocket 握手发生异常: error={}", exception.getMessage());
        }
    }

    /**
     * 按照 Query -> Header -> Cookie 优先级提取 Token
     */
    private String extractToken(ServerHttpRequest request, Map<String, String> queryParams) {
        RealtimeProperties.AuthProperties authProps = (properties != null && properties.getAuth() != null)
                ? properties.getAuth()
                : new RealtimeProperties.AuthProperties();

        List<String> tokenParamNames = (authProps.getTokenQueryParamNames() != null && !authProps.getTokenQueryParamNames().isEmpty())
                ? authProps.getTokenQueryParamNames()
                : List.of("token", "satoken", "access_token");

        // 一阶：Query 参数
        for (String paramName : tokenParamNames) {
            String val = queryParams.get(paramName);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }

        // 二阶：Header 请求头（自定义 headerName + tokenPrefix 或 Sec-WebSocket-Protocol）
        String headerName = (authProps.getHeaderName() != null && !authProps.getHeaderName().isBlank())
                ? authProps.getHeaderName()
                : "Authorization";
        String tokenPrefix = (authProps.getTokenPrefix() != null)
                ? authProps.getTokenPrefix()
                : "Bearer ";

        String authHeader = request.getHeaders().getFirst(headerName);
        if (authHeader != null && !authHeader.isBlank()) {
            String trimmed = authHeader.trim();
            if (!tokenPrefix.isEmpty() && trimmed.regionMatches(true, 0, tokenPrefix, 0, tokenPrefix.length())) {
                return trimmed.substring(tokenPrefix.length()).trim();
            }
            return trimmed;
        }

        String protocolHeader = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
        if (protocolHeader != null && !protocolHeader.isBlank()) {
            String[] parts = protocolHeader.split(",");
            for (String part : parts) {
                String p = part.trim();
                if (!p.isBlank() && !p.equalsIgnoreCase("bearer")) {
                    return p;
                }
            }
        }

        // 三阶：Cookie
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpServletRequest = servletRequest.getServletRequest();
            Cookie[] cookies = httpServletRequest.getCookies();
            if (cookies != null) {
                String cookieName = authProps.getCookieName();
                for (Cookie cookie : cookies) {
                    if (cookieName != null && cookieName.equalsIgnoreCase(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                        return cookie.getValue().trim();
                    }
                    for (String name : tokenParamNames) {
                        if (name.equalsIgnoreCase(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                            return cookie.getValue().trim();
                        }
                    }
                }
            }
        }

        return null;
    }

    private void handleSecWebSocketProtocol(ServerHttpRequest request, ServerHttpResponse response) {
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols != null && !protocols.isEmpty()) {
            String selectedProtocol = protocols.get(0);
            if (selectedProtocol.contains(",")) {
                selectedProtocol = selectedProtocol.split(",")[0].trim();
            }
            response.getHeaders().set("Sec-WebSocket-Protocol", selectedProtocol);
        }
    }

    private Map<String, String> parseQueryParams(URI uri) {
        if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new LinkedHashMap<>();
        String[] pairs = uri.getRawQuery().split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    params.put(key, val);
                } else if (idx < 0 && !pair.isBlank()) {
                    params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                }
            } catch (Exception ignored) {
            }
        }
        return params;
    }
}
