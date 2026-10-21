package io.github.atengk.boot.realtime.auth;

import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.websocket.AtengWebSocketHandler;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * WebSocket 原生握手拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("AtengHandshakeInterceptor 握手安全拦截器测试")
class AtengHandshakeInterceptorTest {

    private final WebSocketHandler wsHandler = mock(WebSocketHandler.class);

    @Test
    @DisplayName("Query 参数 Token 提取与鉴权成功")
    void testQueryTokenSuccess() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeAuthHandshakeProvider authProvider = mock(RealtimeAuthHandshakeProvider.class);
        when(authProvider.authenticate(eq("token-query"), any())).thenReturn(RealtimeAuthPayload.of("user-query", "pc", 100L));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("authProvider", authProvider);

        AtengHandshakeInterceptor interceptor = new AtengHandshakeInterceptor(properties, beanFactory.getBeanProvider(RealtimeAuthHandshakeProvider.class));

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws/realtime?token=token-query&clientId=c-001&deviceType=pc"));
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        Map<String, Object> attributes = new HashMap<>();
        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(pass).isTrue();
        SessionKey key = (SessionKey) attributes.get(AtengWebSocketHandler.SESSION_KEY_ATTR);
        assertThat(key).isNotNull();
        assertThat(key.getUserId()).isEqualTo("user-query");
        assertThat(key.getClientId()).isEqualTo("c-001");
        assertThat(key.getDeviceType()).isEqualTo("pc");
        assertThat(attributes.get(AtengWebSocketHandler.TENANT_ID_ATTR)).isEqualTo(100L);
    }

    @Test
    @DisplayName("Header 请求头 (Authorization: Bearer xxx) Token 提取")
    void testHeaderTokenSuccess() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeAuthHandshakeProvider authProvider = mock(RealtimeAuthHandshakeProvider.class);
        when(authProvider.authenticate(eq("token-bearer-xyz"), any())).thenReturn(RealtimeAuthPayload.of("user-bearer", "mobile", 200L));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("authProvider", authProvider);

        AtengHandshakeInterceptor interceptor = new AtengHandshakeInterceptor(properties, beanFactory.getBeanProvider(RealtimeAuthHandshakeProvider.class));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer token-bearer-xyz");

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws/realtime"));
        when(request.getHeaders()).thenReturn(headers);

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        Map<String, Object> attributes = new HashMap<>();
        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(pass).isTrue();
        SessionKey key = (SessionKey) attributes.get(AtengWebSocketHandler.SESSION_KEY_ATTR);
        assertThat(key).isNotNull();
        assertThat(key.getUserId()).isEqualTo("user-bearer");
    }

    @Test
    @DisplayName("Cookie (satoken) 凭据提取")
    void testCookieTokenSuccess() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeAuthHandshakeProvider authProvider = mock(RealtimeAuthHandshakeProvider.class);
        when(authProvider.authenticate(eq("token-cookie-123"), any())).thenReturn(RealtimeAuthPayload.of("user-cookie", "web", 300L));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("authProvider", authProvider);

        AtengHandshakeInterceptor interceptor = new AtengHandshakeInterceptor(properties, beanFactory.getBeanProvider(RealtimeAuthHandshakeProvider.class));

        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getCookies()).thenReturn(new Cookie[]{new Cookie("satoken", "token-cookie-123")});

        ServletServerHttpRequest request = mock(ServletServerHttpRequest.class);
        when(request.getServletRequest()).thenReturn(servletRequest);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws/realtime"));
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        Map<String, Object> attributes = new HashMap<>();
        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(pass).isTrue();
        SessionKey key = (SessionKey) attributes.get(AtengWebSocketHandler.SESSION_KEY_ATTR);
        assertThat(key).isNotNull();
        assertThat(key.getUserId()).isEqualTo("user-cookie");
    }

    @Test
    @DisplayName("优先级验证：Query 优先于 Header，Header 优先于 Cookie")
    void testExtractionPrecedence() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeAuthHandshakeProvider authProvider = mock(RealtimeAuthHandshakeProvider.class);
        when(authProvider.authenticate(eq("token-from-query"), any())).thenReturn(RealtimeAuthPayload.of("winner-query"));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("authProvider", authProvider);

        AtengHandshakeInterceptor interceptor = new AtengHandshakeInterceptor(properties, beanFactory.getBeanProvider(RealtimeAuthHandshakeProvider.class));

        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getCookies()).thenReturn(new Cookie[]{new Cookie("satoken", "token-from-cookie")});

        ServletServerHttpRequest request = mock(ServletServerHttpRequest.class);
        when(request.getServletRequest()).thenReturn(servletRequest);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws/realtime?token=token-from-query"));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer token-from-header");
        when(request.getHeaders()).thenReturn(headers);

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        Map<String, Object> attributes = new HashMap<>();
        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(pass).isTrue();
        verify(authProvider, times(1)).authenticate(eq("token-from-query"), any());
        verify(authProvider, never()).authenticate(eq("token-from-header"), any());
        verify(authProvider, never()).authenticate(eq("token-from-cookie"), any());
    }

    @Test
    @DisplayName("Sec-WebSocket-Protocol 协议头回执")
    void testSecWebSocketProtocolEcho() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getAuth().setAllowAnonymous(true);
        AtengHandshakeInterceptor interceptor = new AtengHandshakeInterceptor(properties, new StaticListableBeanFactory().getBeanProvider(RealtimeAuthHandshakeProvider.class));

        HttpHeaders reqHeaders = new HttpHeaders();
        reqHeaders.put("Sec-WebSocket-Protocol", List.of("custom-proto-v1, auth-token-xxx"));

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws/realtime"));
        when(request.getHeaders()).thenReturn(reqHeaders);

        HttpHeaders respHeaders = new HttpHeaders();
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(respHeaders);

        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertThat(pass).isTrue();
        assertThat(respHeaders.getFirst("Sec-WebSocket-Protocol")).isEqualTo("custom-proto-v1");
    }

    @Test
    @DisplayName("Token 无效鉴权失败返回 401 拒绝握手")
    void testAuthFailedRejection() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeAuthHandshakeProvider authProvider = mock(RealtimeAuthHandshakeProvider.class);
        when(authProvider.authenticate(eq("bad-token"), any())).thenReturn(null);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("authProvider", authProvider);

        AtengHandshakeInterceptor interceptor = new AtengHandshakeInterceptor(properties, beanFactory.getBeanProvider(RealtimeAuthHandshakeProvider.class));

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws/realtime?token=bad-token"));
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertThat(pass).isFalse();
        verify(response, times(1)).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("未传 Token 时匿名配置开闭分支判定")
    void testAnonymousSwitch() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        AtengHandshakeInterceptor interceptor = new AtengHandshakeInterceptor(properties, new StaticListableBeanFactory().getBeanProvider(RealtimeAuthHandshakeProvider.class));

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/ws/realtime"));
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());

        // 默认 allowAnonymous = false -> 拒绝 401
        boolean passRejected = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());
        assertThat(passRejected).isFalse();
        verify(response, times(1)).setStatusCode(HttpStatus.UNAUTHORIZED);

        // 设置 allowAnonymous = true -> 放行并注入匿名 SessionKey
        properties.getAuth().setAllowAnonymous(true);
        Map<String, Object> attributes = new HashMap<>();
        boolean passAllowed = interceptor.beforeHandshake(request, response, wsHandler, attributes);
        assertThat(passAllowed).isTrue();
        SessionKey key = (SessionKey) attributes.get(AtengWebSocketHandler.SESSION_KEY_ATTR);
        assertThat(key).isNotNull();
        assertThat(key.isAnonymous()).isTrue();
    }
}
