package io.github.atengk.boot.realtime.sse;

import io.github.atengk.boot.realtime.auth.RealtimeAuthHandshakeProvider;
import io.github.atengk.boot.realtime.auth.RealtimeAuthPayload;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import io.github.atengk.boot.realtime.session.RealtimeSession;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 原生 SSE 端点控制器单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("AtengSseController 端点控制器测试")
class AtengSseControllerTest {

    @Test
    @DisplayName("已认证用户建立 SSE 连接并自动托管会话")
    void testAuthorizedUserConnect() {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        AtengSseController controller = new AtengSseController(properties, sessionManager);

        HttpServletRequest request = mock(HttpServletRequest.class);
        SseEmitter emitter = controller.connect("c-001", "u-001", "web", 100L, request);

        assertThat(emitter).isNotNull();
        SessionKey key = SessionKey.of("u-001", "web", "c-001");
        RealtimeSession session = sessionManager.getSession(key);
        assertThat(session).isNotNull();
        assertThat(session.getTenantId()).isEqualTo(100L);
        assertThat(session.isOpen()).isTrue();
    }

    @Test
    @DisplayName("匿名配置开启时允许未登录访客连接")
    void testAnonymousAllowed() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getAuth().setAllowAnonymous(true);
        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        AtengSseController controller = new AtengSseController(properties, sessionManager);

        HttpServletRequest request = mock(HttpServletRequest.class);
        SseEmitter emitter = controller.connect("anon-client-1", null, null, null, request);

        assertThat(emitter).isNotNull();
        SessionKey key = SessionKey.anonymous("anon-client-1");
        RealtimeSession session = sessionManager.getSession(key);
        assertThat(session).isNotNull();
        assertThat(session.getSessionKey().isAnonymous()).isTrue();
    }

    @Test
    @DisplayName("匿名配置关闭时拒绝未登录连接请求 (401 Unauthorized)")
    void testAnonymousRejected() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getAuth().setAllowAnonymous(false);
        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        AtengSseController controller = new AtengSseController(properties, sessionManager);

        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> controller.connect("anon-client-2", null, null, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }

    @Test
    @DisplayName("从 Request Attribute 中提取已鉴权用户身份")
    void testUserExtractedFromRequestAttribute() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getAuth().setAllowAnonymous(false);
        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        AtengSseController controller = new AtengSseController(properties, sessionManager);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("userId")).thenReturn("u-attr-888");

        SseEmitter emitter = controller.connect("c-888", null, "mobile", null, request);

        assertThat(emitter).isNotNull();
        SessionKey key = SessionKey.of("u-attr-888", "mobile", "c-888");
        RealtimeSession session = sessionManager.getSession(key);
        assertThat(session).isNotNull();
    }

    @Test
    @DisplayName("MockMvc HTTP GET /sse/realtime 端点调用")
    void testMockMvcGetSseEndpoint() throws Exception {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        AtengSseController controller = new AtengSseController(properties, sessionManager);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/sse/realtime")
                        .param("userId", "u-mvc")
                        .param("clientId", "c-mvc"))
                .andExpect(status().isOk());

        SessionKey key = SessionKey.of("u-mvc", "c-mvc");
        assertThat(sessionManager.getSession(key)).isNotNull();
    }

    @Test
    @DisplayName("自定义 HeaderName 与 TokenPrefix 凭据提取与鉴权成功")
    void testCustomHeaderAndPrefixAuth() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getAuth().setHeaderName("X-Custom-Auth");
        properties.getAuth().setTokenPrefix("CustomToken ");
        properties.getAuth().setAllowAnonymous(false);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeAuthHandshakeProvider authProvider = mock(RealtimeAuthHandshakeProvider.class);
        when(authProvider.authenticate("secret-123456")).thenReturn(RealtimeAuthPayload.of("u-custom-header", "browser", 999L));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("authProvider", authProvider);

        AtengSseController controller = new AtengSseController(properties, sessionManager, beanFactory.getBeanProvider(RealtimeAuthHandshakeProvider.class));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Custom-Auth")).thenReturn("CustomToken secret-123456");

        SseEmitter emitter = controller.connect("c-custom", null, null, null, request);
        assertThat(emitter).isNotNull();

        SessionKey key = SessionKey.of("u-custom-header", "browser", "c-custom");
        RealtimeSession session = sessionManager.getSession(key);
        assertThat(session).isNotNull();
        assertThat(session.getTenantId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("自定义 CookieName 凭据提取与鉴权成功")
    void testCustomCookieAuth() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.getAuth().setCookieName("my_session_token");
        properties.getAuth().setAllowAnonymous(false);

        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        RealtimeAuthHandshakeProvider authProvider = mock(RealtimeAuthHandshakeProvider.class);
        when(authProvider.authenticate("cookie-token-val")).thenReturn(RealtimeAuthPayload.of("u-cookie-user", "desktop", 777L));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("authProvider", authProvider);

        AtengSseController controller = new AtengSseController(properties, sessionManager, beanFactory.getBeanProvider(RealtimeAuthHandshakeProvider.class));

        HttpServletRequest request = mock(HttpServletRequest.class);
        Cookie[] cookies = new Cookie[]{new Cookie("my_session_token", "cookie-token-val")};
        when(request.getCookies()).thenReturn(cookies);

        SseEmitter emitter = controller.connect("c-cookie", null, null, null, request);
        assertThat(emitter).isNotNull();

        SessionKey key = SessionKey.of("u-cookie-user", "desktop", "c-cookie");
        RealtimeSession session = sessionManager.getSession(key);
        assertThat(session).isNotNull();
        assertThat(session.getTenantId()).isEqualTo(777L);
    }

    @Test
    @DisplayName("重连携带 Last-Event-ID 触发离线消息只读重放")
    void testLastEventIdReplay() {
        RealtimeProperties properties = new RealtimeProperties();
        RealtimeSessionManager sessionManager = new RealtimeSessionManager(properties);
        io.github.atengk.boot.realtime.spi.RealtimeOfflineMessageProvider mockOfflineProvider =
                mock(io.github.atengk.boot.realtime.spi.RealtimeOfflineMessageProvider.class);

        io.github.atengk.boot.realtime.model.RealtimeMessage<String> missedMsg =
                io.github.atengk.boot.realtime.model.RealtimeMessage.of("news", "missed update");
        when(mockOfflineProvider.getOfflineMessagesSince("u-replay", "1700000000000"))
                .thenReturn(java.util.List.of(missedMsg));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("offlineProvider", mockOfflineProvider);

        AtengSseController controller = new AtengSseController(
                properties,
                sessionManager,
                null,
                null,
                beanFactory.getBeanProvider(io.github.atengk.boot.realtime.spi.RealtimeOfflineMessageProvider.class)
        );

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Last-Event-ID")).thenReturn("1700000000000");

        SseEmitter emitter = controller.connect("c-replay", "u-replay", "web", null, request);
        assertThat(emitter).isNotNull();

        verify(mockOfflineProvider).getOfflineMessagesSince("u-replay", "1700000000000");
    }
}
