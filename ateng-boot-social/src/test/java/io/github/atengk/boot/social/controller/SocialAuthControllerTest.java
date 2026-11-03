package io.github.atengk.boot.social.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialBindRequest;
import io.github.atengk.boot.social.model.SocialDirectLoginRequest;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialUnbindRequest;
import io.github.atengk.boot.social.model.SocialUser;
import io.github.atengk.boot.social.model.StateContext;
import io.github.atengk.boot.social.template.SocialAuthTemplate;
import org.dromara.anduin.model.AuthCallback;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

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
        authTemplate = mock(SocialAuthTemplate.class);
        SocialAuthController controller = new SocialAuthController(authTemplate);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new ParameterMessageInterpolator());
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("1. render: 渲染授权跳转 URL 端点")
    void testRenderAuthorizeUrl() throws Exception {
        when(authTemplate.getAuthorizeUrl(eq("github_app"), any(StateContext.class)))
                .thenReturn("https://github.com/login/oauth/authorize?client_id=123&state=xyz");

        mockMvc.perform(get("/auth/social/github_app/render")
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

        mockMvc.perform(get("/auth/social/github_app/callback")
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

        mockMvc.perform(post("/auth/social/wechat_mp_app/direct-login")
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

        mockMvc.perform(post("/auth/social/wechat_mp_app/direct-login")
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

        mockMvc.perform(post("/auth/social/github_app/bind")
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

        mockMvc.perform(post("/auth/social/github_app/bind")
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

        mockMvc.perform(post("/auth/social/github_app/unbind")
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

        mockMvc.perform(post("/auth/social/github_app/unbind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }
}
