package io.github.atengk.boot.social.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialBindRequest;
import io.github.atengk.boot.social.model.SocialBoundAccount;
import io.github.atengk.boot.social.model.SocialDirectBindRequest;
import io.github.atengk.boot.social.model.SocialDirectLoginRequest;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialToken;
import io.github.atengk.boot.social.model.SocialTokenRefreshRequest;
import io.github.atengk.boot.social.model.SocialTokenRevokeRequest;
import io.github.atengk.boot.social.model.SocialUnbindRequest;
import io.github.atengk.boot.social.model.SocialUser;
import io.github.atengk.boot.social.model.StateContext;
import io.github.atengk.boot.social.template.SocialAuthTemplate;
import io.github.atengk.boot.social.test.MockSaTokenTestHelper;
import org.dromara.anduin.model.AuthCallback;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SocialAuthController REST 端点契约单元测试
 * 验证强类型 Result 响应结构与 JSR-303 参数校验
 *
 * @author Ateng
 * @since 2026-11-03
 */
class SocialAuthControllerTest {

    private MockMvc mockMvc;
    private SocialAuthTemplate authTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockSaTokenTestHelper.setUp();
        authTemplate = mock(SocialAuthTemplate.class);
        SocialAuthController controller = new SocialAuthController(authTemplate);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new ParameterMessageInterpolator());
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        MockSaTokenTestHelper.tearDown();
    }

    @Test
    @DisplayName("1. render: 渲染授权跳转 URL 端点")
    void testRenderAuthorizeUrl() throws Exception {
        when(authTemplate.getAuthorizeUrl(eq("github_app"), any(StateContext.class)))
                .thenReturn("https://github.com/login/oauth/authorize?client_id=123&state=xyz");

        mockMvc.perform(get("/auth/social/render/github_app")
                        .param("redirectUrl", "https://example.com/dest")
                        .param("tenantId", "tenant_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("https://github.com/login/oauth/authorize?client_id=123&state=xyz"));

        verify(authTemplate).getAuthorizeUrl(eq("github_app"), any(StateContext.class));
    }

    @Test
    @DisplayName("2. callback: 处理 OAuth2 回调换码并显式返回 Result<SocialAuthResult>")
    void testCallback() throws Exception {
        SocialUser mockUser = SocialUser.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_123")
                .username("ateng_dev")
                .build();
        SocialAuthResult mockResult = SocialAuthResult.loggedIn("user_888", "mock_token_str", mockUser, null);

        when(authTemplate.login(eq("github_app"), any(AuthCallback.class)))
                .thenReturn(mockResult);

        mockMvc.perform(get("/auth/social/callback/github_app")
                        .param("code", "mock_code_abc")
                        .param("state", "mock_state_xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("LOGGED_IN"))
                .andExpect(jsonPath("$.data.userId").value("user_888"))
                .andExpect(jsonPath("$.data.socialUser.openId").value("gh_123"));

        verify(authTemplate).login(eq("github_app"), any(AuthCallback.class));
    }

    @Test
    @DisplayName("3. directLogin: 小程序/App凭证直传登录")
    void testDirectLogin() throws Exception {
        SocialUser mockUser = SocialUser.builder()
                .appKey("wechat_mp_app")
                .source("WECHAT_MINI_PROGRAM")
                .openId("wx_open_456")
                .build();
        SocialAuthResult mockResult = SocialAuthResult.loggedIn("user_wx_001", "token_wx", mockUser, null);

        when(authTemplate.directLogin(eq("wechat_mp_app"), eq("js_code_123"), any(SocialLoginOptions.class)))
                .thenReturn(mockResult);

        SocialDirectLoginRequest request = SocialDirectLoginRequest.builder()
                .code("js_code_123")
                .deviceType("MINI_PROGRAM")
                .loginType("USER")
                .build();

        mockMvc.perform(post("/auth/social/direct-login/wechat_mp_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("user_wx_001"))
                .andExpect(jsonPath("$.data.socialUser.openId").value("wx_open_456"));
    }

    @Test
    @DisplayName("4. directLogin: code 为空时触发 JSR-303 参数校验拦截")
    void testDirectLoginValidation() throws Exception {
        SocialDirectLoginRequest invalidRequest = SocialDirectLoginRequest.builder()
                .code("   ")
                .build();

        mockMvc.perform(post("/auth/social/direct-login/wechat_mp_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5. bind: 消费 BindTicket 完成两阶段绑定")
    void testBind() throws Exception {
        SocialUser mockUser = SocialUser.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_new_999")
                .build();
        SocialAuthResult mockResult = SocialAuthResult.loggedIn("local_user_666", "token_666", mockUser, null);

        when(authTemplate.bind(eq("github_app"), eq("ticket_777"), eq("local_user_666"), any(SocialLoginOptions.class)))
                .thenReturn(mockResult);

        SocialBindRequest request = SocialBindRequest.builder()
                .bindTicket("ticket_777")
                .userId("local_user_666")
                .deviceType("PC")
                .build();

        mockMvc.perform(post("/auth/social/bind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("local_user_666"));
    }

    @Test
    @DisplayName("6. bind: 必填字段缺失时触发 JSR-303 参数校验拦截")
    void testBindValidation() throws Exception {
        SocialBindRequest invalidRequest = SocialBindRequest.builder()
                .bindTicket("")
                .userId("u1")
                .build();

        mockMvc.perform(post("/auth/social/bind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("7. unbind: 解除社交绑定端点")
    void testUnbind() throws Exception {
        SocialUnbindRequest request = SocialUnbindRequest.builder()
                .userId("local_user_888")
                .build();

        mockMvc.perform(post("/auth/social/unbind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(authTemplate).unbind("local_user_888", "github_app");
    }

    @Test
    @DisplayName("8. unbind: userId 为空时触发 JSR-303 参数校验拦截")
    void testUnbindValidation() throws Exception {
        SocialUnbindRequest invalidRequest = SocialUnbindRequest.builder()
                .userId("  ")
                .build();

        mockMvc.perform(post("/auth/social/unbind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("9. refresh: 刷新第三方访问令牌端点")
    void testRefresh() throws Exception {
        SocialToken mockToken = SocialToken.builder()
                .accessToken("new_access_token_777")
                .refreshToken("new_refresh_token_888")
                .expireIn(3600)
                .openId("open_999")
                .build();

        when(authTemplate.refreshToken(eq("github_app"), eq("old_refresh_token_123")))
                .thenReturn(mockToken);

        SocialTokenRefreshRequest request = SocialTokenRefreshRequest.builder()
                .refreshToken("old_refresh_token_123")
                .build();

        mockMvc.perform(post("/auth/social/refresh/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("new_access_token_777"))
                .andExpect(jsonPath("$.data.refreshToken").value("new_refresh_token_888"))
                .andExpect(jsonPath("$.data.expireIn").value(3600))
                .andExpect(jsonPath("$.data.openId").value("open_999"));

        verify(authTemplate).refreshToken("github_app", "old_refresh_token_123");
    }

    @Test
    @DisplayName("10. refresh: refreshToken 为空时触发 JSR-303 参数校验拦截")
    void testRefreshValidation() throws Exception {
        SocialTokenRefreshRequest invalidRequest = SocialTokenRefreshRequest.builder()
                .refreshToken("  ")
                .build();

        mockMvc.perform(post("/auth/social/refresh/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("11. revoke: 吊销第三方授权凭证端点")
    void testRevoke() throws Exception {
        SocialTokenRevokeRequest request = SocialTokenRevokeRequest.builder()
                .accessToken("access_token_to_revoke")
                .build();

        mockMvc.perform(post("/auth/social/revoke/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(authTemplate).revokeAuth("github_app", "access_token_to_revoke");
    }

    @Test
    @DisplayName("12. revoke: accessToken 为空时触发 JSR-303 参数校验拦截")
    void testRevokeValidation() throws Exception {
        SocialTokenRevokeRequest invalidRequest = SocialTokenRevokeRequest.builder()
                .accessToken("   ")
                .build();

        mockMvc.perform(post("/auth/social/revoke/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("13. bindDirect: 个人中心已登录直绑（纯 code 直传模式）")
    void testBindDirectWithCode() throws Exception {
        SocialUser mockUser = SocialUser.builder()
                .appKey("wechat_mp_app")
                .source("WECHAT_MINI_PROGRAM")
                .openId("wx_bound_direct_100")
                .username("wxDirectUser")
                .build();

        when(authTemplate.bindDirect(eq("user_local_100"), eq("wechat_mp_app"), eq("code_direct_123")))
                .thenReturn(mockUser);

        SocialDirectBindRequest request = SocialDirectBindRequest.builder()
                .userId("user_local_100")
                .code("code_direct_123")
                .build();

        mockMvc.perform(post("/auth/social/bind-direct/wechat_mp_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.openId").value("wx_bound_direct_100"))
                .andExpect(jsonPath("$.data.appKey").value("wechat_mp_app"));

        verify(authTemplate).bindDirect("user_local_100", "wechat_mp_app", "code_direct_123");
    }

    @Test
    @DisplayName("14. bindDirect: 个人中心已登录直绑（携带 state 回调参数模式）")
    void testBindDirectWithCallback() throws Exception {
        SocialUser mockUser = SocialUser.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_bound_direct_200")
                .username("ghDirectUser")
                .build();

        when(authTemplate.bindDirect(eq("user_local_200"), eq("github_app"), any(AuthCallback.class)))
                .thenReturn(mockUser);

        SocialDirectBindRequest request = SocialDirectBindRequest.builder()
                .userId("user_local_200")
                .code("code_web_callback")
                .state("state_web_csrf")
                .build();

        mockMvc.perform(post("/auth/social/bind-direct/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.openId").value("gh_bound_direct_200"));

        verify(authTemplate).bindDirect(eq("user_local_200"), eq("github_app"), any(AuthCallback.class));
    }

    @Test
    @DisplayName("15. bindDirect: 必填字段缺失触发 JSR-303 参数校验拦截")
    void testBindDirectValidation() throws Exception {
        SocialDirectBindRequest invalidReq1 = SocialDirectBindRequest.builder()
                .userId("   ")
                .code("code_123")
                .build();

        mockMvc.perform(post("/auth/social/bind-direct/wechat_mp_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq1)))
                .andExpect(status().isBadRequest());

        SocialDirectBindRequest invalidReq2 = SocialDirectBindRequest.builder()
                .userId("user_123")
                .code("   ")
                .build();

        mockMvc.perform(post("/auth/social/bind-direct/wechat_mp_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("16. listBindings: 显式传入 userId 查询绑定账号列表")
    void testListBindingsWithExplicitUserId() throws Exception {
        SocialBoundAccount account = SocialBoundAccount.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_open_300")
                .username("ateng300")
                .nickname("Ateng 300")
                .avatar("https://avatar.example.com/300.png")
                .build();

        when(authTemplate.listBoundAccounts("user_query_100"))
                .thenReturn(List.of(account));

        mockMvc.perform(get("/auth/social/bindings")
                        .param("userId", "user_query_100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].appKey").value("github_app"))
                .andExpect(jsonPath("$.data[0].openId").value("gh_open_300"))
                .andExpect(jsonPath("$.data[0].nickname").value("Ateng 300"));

        verify(authTemplate).listBoundAccounts("user_query_100");
    }

    @Test
    @DisplayName("17. listBindings: 缺省 userId 自动从当前 Sa-Token 会话中解析")
    void testListBindingsFromSaTokenSession() throws Exception {
        StpUtil.login("user_satoken_999");

        when(authTemplate.listBoundAccounts("user_satoken_999"))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/auth/social/bindings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(authTemplate).listBoundAccounts("user_satoken_999");
    }
}
