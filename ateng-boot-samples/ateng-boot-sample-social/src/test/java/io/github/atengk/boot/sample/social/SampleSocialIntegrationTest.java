package io.github.atengk.boot.sample.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.sample.social.listener.SampleSocialEventListener;
import io.github.atengk.boot.sample.social.service.SampleSocialScenarioService;
import io.github.atengk.boot.sample.social.service.SampleSocialUserDetailsService;
import io.github.atengk.boot.social.code.SocialResultCode;
import io.github.atengk.boot.social.config.SocialAppConfig;
import io.github.atengk.boot.social.http.SpringRestClientHttpImpl;
import io.github.atengk.boot.social.model.SocialBindRequest;
import io.github.atengk.boot.social.model.SocialDirectBindRequest;
import io.github.atengk.boot.social.model.SocialDirectLoginRequest;
import io.github.atengk.boot.social.model.SocialTokenRefreshRequest;
import io.github.atengk.boot.social.model.SocialTokenRevokeRequest;
import io.github.atengk.boot.social.model.SocialUnbindRequest;
import io.github.atengk.boot.social.model.StateContext;
import io.github.atengk.boot.social.template.SocialAuthTemplate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.dromara.anduin.http.HttpClientAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 社交登录自验样例工程端到端集成测试
 * <p>
 * 依托 MockWebServer 模拟第三方 OAuth2 服务端与微信小程序服务端接口，
 * 100% 覆盖内置 {@code SocialAuthController} 以及按业务领域拆分的四大 Controller：
 * 1. {@link io.github.atengk.boot.sample.social.controller.SocialWebOAuthController}
 * 2. {@link io.github.atengk.boot.sample.social.controller.SocialMiniProgramAuthController}
 * 3. {@link io.github.atengk.boot.sample.social.controller.SocialAccountBindController}
 * 4. {@link io.github.atengk.boot.sample.social.controller.SocialMultiTenantConfigController}
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest
@AutoConfigureMockMvc
class SampleSocialIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HttpClientAdapter httpClientAdapter;

    @Autowired
    private SocialAuthTemplate socialAuthTemplate;

    @Autowired
    private SampleSocialEventListener eventListener;

    @Autowired
    private SampleSocialUserDetailsService userDetailsService;

    @Autowired
    private SampleSocialScenarioService scenarioService;

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        if (httpClientAdapter instanceof SpringRestClientHttpImpl springClient) {
            springClient.setUrlRewriter(originalUrl -> {
                try {
                    URI uri = URI.create(originalUrl);
                    String pathAndQuery = (uri.getRawPath() != null ? uri.getRawPath() : "")
                            + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
                    return mockWebServer.url(pathAndQuery).toString();
                } catch (Exception ex) {
                    return originalUrl;
                }
            });
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (httpClientAdapter instanceof SpringRestClientHttpImpl springClient) {
            springClient.setUrlRewriter(null);
        }
        mockWebServer.shutdown();
        eventListener.reset();
        userDetailsService.reset();
        TenantContextHolder.clear();
    }

    // ==================== 一、内置受管 SocialAuthController 端点验证 ====================

    @Test
    @DisplayName("1. [内置端点] /auth/social/render/{appKey}: 生成第三方授权地址")
    void test1_BuiltIn_RenderAuthorizeUrl() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/auth/social/render/github_app")
                        .param("redirectUrl", "https://ateng.example.com/dashboard")
                        .param("tenantId", "tenant_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String content = mvcResult.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        String authorizeUrl = root.get("data").asText();

        assertThat(authorizeUrl)
                .contains("github.com/login/oauth/authorize")
                .contains("client_id=sample_github_client_id")
                .contains("state=");
    }

    @Test
    @DisplayName("2. [内置端点] /auth/social/callback/{appKey}: Web 回调换码认证并签发登录会话")
    void test2_BuiltIn_WebOAuth2LoginBoundUser() throws Exception {
        String authorizeUrl = socialAuthTemplate.getAuthorizeUrl("github_app");
        String state = extractQueryParam(authorizeUrl, "state");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gh_token_1001\",\"token_type\":\"bearer\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":1001,\"login\":\"ateng_normal\",\"name\":\"阿腾测试用户\"}"));

        mockMvc.perform(get("/auth/social/callback/github_app")
                        .param("code", "mock_oauth_code_1001")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("LOGGED_IN"))
                .andExpect(jsonPath("$.data.userId").value("user_bound_1001"))
                .andExpect(jsonPath("$.data.socialUser.openId").value("1001"))
                .andExpect(jsonPath("$.data.tokenInfo").isNotEmpty());
    }

    @Test
    @DisplayName("3. [内置端点] /auth/social/bind/{appKey}: 两阶段绑定与阅后即焚防重放")
    void test3_BuiltIn_TwoPhaseBindFlowAndReplayDefense() throws Exception {
        String authorizeUrl = socialAuthTemplate.getAuthorizeUrl("github_app");
        String state = extractQueryParam(authorizeUrl, "state");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gh_token_3003\",\"token_type\":\"bearer\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":3003,\"login\":\"new_registered_user\",\"name\":\"新注册用户\"}"));

        MvcResult callbackResult = mockMvc.perform(get("/auth/social/callback/github_app")
                        .param("code", "mock_code_3003")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("REQUIRE_BIND"))
                .andExpect(jsonPath("$.data.bindTicket").isNotEmpty())
                .andReturn();

        JsonNode callbackData = objectMapper.readTree(callbackResult.getResponse().getContentAsString()).get("data");
        String bindTicket = callbackData.get("bindTicket").asText();

        SocialBindRequest bindRequest = SocialBindRequest.builder()
                .bindTicket(bindTicket)
                .userId("user_brand_new_555")
                .deviceType("PC")
                .build();

        mockMvc.perform(post("/auth/social/bind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bindRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("LOGGED_IN"))
                .andExpect(jsonPath("$.data.userId").value("user_brand_new_555"));

        // 对抗性防重放验证：重复核销已销毁的 bindTicket 必须被拦截阻断
        mockMvc.perform(post("/auth/social/bind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bindRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SocialResultCode.SOCIAL_BIND_TICKET_INVALID.getCode()));
    }

    @Test
    @DisplayName("4. [内置端点] /auth/social/direct-login/{appKey}: 微信小程序凭据直传换码")
    void test4_BuiltIn_MiniProgramDirectLogin() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"openid\":\"wx_open_mini_888\",\"session_key\":\"sess_888\"}"));

        SocialDirectLoginRequest request = SocialDirectLoginRequest.builder()
                .code("mock_js_code_888")
                .deviceType("MINI_PROGRAM")
                .build();

        mockMvc.perform(post("/auth/social/direct-login/wechat_mp_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("REQUIRE_BIND"))
                .andExpect(jsonPath("$.data.bindTicket").isNotEmpty())
                .andExpect(jsonPath("$.data.socialUser.openId").value("wx_open_mini_888"));
    }

    @Test
    @DisplayName("5. [内置端点] /auth/social/unbind/{appKey}: 孤儿死号安全防卫与正常解绑")
    void test5_BuiltIn_UnbindFlow() throws Exception {
        // 5a. 孤儿账号解绑拦截
        SocialUnbindRequest unbindRequest = SocialUnbindRequest.builder()
                .userId("user_orphan_2002")
                .build();

        mockMvc.perform(post("/auth/social/unbind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unbindRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SocialResultCode.SOCIAL_UNBIND_REJECTED.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("防止账号孤儿化")));

        assertThat(eventListener.getUnboundCount()).isEqualTo(0);

        // 5b. 具备备用凭据的账号正常解绑
        SocialUnbindRequest successUnbind = SocialUnbindRequest.builder()
                .userId("user_bound_1001")
                .build();

        mockMvc.perform(post("/auth/social/unbind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(successUnbind)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(eventListener.getUnboundCount()).isEqualTo(1);
    }

    // ==================== 二、业务领域 1: SocialWebOAuthController 验证 ====================

    @Test
    @DisplayName("6. [WebOAuth] /sample/social/oauth/render/{appKey}: PC 扫码授权 URL 生成")
    void test6_WebOAuth_RenderAuthorizeUrl() throws Exception {
        mockMvc.perform(get("/sample/social/oauth/render/github_app")
                        .param("redirectUrl", "https://ateng.example.com/welcome")
                        .param("tenantId", "tenant_sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("github.com/login/oauth/authorize")))
                .andExpect(jsonPath("$.data").value(containsString("state=")));
    }

    @Test
    @DisplayName("7. [WebOAuth] /sample/social/oauth/render-whitelist: 开放重定向白名单校验双向验证")
    void test7_WebOAuth_WhitelistDefense() throws Exception {
        // 7a. 合法白名单域名 -> 成功生成
        mockMvc.perform(get("/sample/social/oauth/render-whitelist")
                        .param("redirectUrl", "https://ateng.example.com/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("state=")));

        // 7b. 非法外部钓鱼域名 -> 触发 7008 拦截
        mockMvc.perform(get("/sample/social/oauth/render-whitelist")
                        .param("redirectUrl", "https://malicious-evil.com/hack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SocialResultCode.SOCIAL_REDIRECT_URI_INVALID.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("不在允许的白名单列表中")));
    }

    @Test
    @DisplayName("8. [WebOAuth] /sample/social/oauth/callback/{appKey}: Web 回调换码成功登入")
    void test8_WebOAuth_CallbackLogin() throws Exception {
        String authorizeUrl = socialAuthTemplate.getAuthorizeUrl("github_app");
        String state = extractQueryParam(authorizeUrl, "state");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gh_token_1001\",\"token_type\":\"bearer\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":1001,\"login\":\"ateng_normal\",\"name\":\"阿腾测试用户\"}"));

        mockMvc.perform(get("/sample/social/oauth/callback/github_app")
                        .param("code", "mock_oauth_code_web")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("LOGGED_IN"))
                .andExpect(jsonPath("$.data.userId").value("user_bound_1001"));
    }

    @Test
    @DisplayName("9. [WebOAuth] 令牌续期与授权吊销: /refresh-token 与 /revoke-token")
    void test9_WebOAuth_RefreshTokenAndRevokeAuth() throws Exception {
        // 9a. 令牌续期 (Baidu 平台模拟)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"baidu_new_access_token\",\"refresh_token\":\"baidu_new_refresh_token\",\"expires_in\":2592000}"));

        SocialTokenRefreshRequest refreshRequest = SocialTokenRefreshRequest.builder()
                .refreshToken("baidu_old_refresh_token")
                .build();

        mockMvc.perform(post("/sample/social/oauth/refresh-token/baidu_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("baidu_new_access_token"))
                .andExpect(jsonPath("$.data.refreshToken").value("baidu_new_refresh_token"));

        // 9b. 授权吊销 (Baidu 平台模拟)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"result\":1}"));

        SocialTokenRevokeRequest revokeRequest = SocialTokenRevokeRequest.builder()
                .accessToken("baidu_new_access_token")
                .build();

        mockMvc.perform(post("/sample/social/oauth/revoke-token/baidu_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(revokeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 三、业务领域 2: SocialMiniProgramAuthController 验证 ====================

    @Test
    @DisplayName("10. [MiniApp] /sample/social/miniapp/direct-login/{appKey}: 小程序凭据直传登录")
    void test10_MiniApp_DirectLogin() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"openid\":\"wx_miniapp_open_777\",\"session_key\":\"sess_777\"}"));

        SocialDirectLoginRequest request = SocialDirectLoginRequest.builder()
                .code("mock_wx_js_code")
                .deviceType("MINI_PROGRAM")
                .build();

        mockMvc.perform(post("/sample/social/miniapp/direct-login/wechat_mp_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("REQUIRE_BIND"))
                .andExpect(jsonPath("$.data.bindTicket").isNotEmpty());
    }

    @Test
    @DisplayName("11. [MiniApp] /sample/social/miniapp/auto-signup-login/{appKey}: 首次登录静默自动注册开户 (Auto-Signup)")
    void test11_MiniApp_AutoSignUpLogin() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"openid\":\"wx_auto_open_999\",\"session_key\":\"sess_auto_999\"}"));

        SocialDirectLoginRequest request = SocialDirectLoginRequest.builder()
                .code("mock_wx_auto_code")
                .deviceType("MINI_PROGRAM")
                .build();

        // 调用开启了 auto-sign-up 的 wechat_auto_app
        mockMvc.perform(post("/sample/social/miniapp/auto-signup-login/wechat_auto_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("LOGGED_IN"))
                .andExpect(jsonPath("$.data.userId").value(containsString("auto_user_")))
                .andExpect(jsonPath("$.data.socialUser.openId").value("wx_auto_open_999"))
                .andExpect(jsonPath("$.data.tokenInfo").isNotEmpty());
    }

    // ==================== 四、业务领域 3: SocialAccountBindController 验证 ====================

    @Test
    @DisplayName("12. [AccountBind] /sample/social/bind/two-phase-bind/{appKey}: 两阶段绑定完成与登录")
    void test12_AccountBind_TwoPhaseBind() throws Exception {
        String authorizeUrl = socialAuthTemplate.getAuthorizeUrl("github_app");
        String state = extractQueryParam(authorizeUrl, "state");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gh_token_bind_888\",\"token_type\":\"bearer\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":88801,\"login\":\"bind_user_gh\",\"name\":\"绑定测试\"}"));

        MvcResult callbackResult = mockMvc.perform(get("/sample/social/oauth/callback/github_app")
                        .param("code", "mock_bind_code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(callbackResult.getResponse().getContentAsString()).get("data");
        String bindTicket = data.get("bindTicket").asText();

        SocialBindRequest bindRequest = SocialBindRequest.builder()
                .bindTicket(bindTicket)
                .userId("user_bind_target_01")
                .deviceType("PC")
                .build();

        mockMvc.perform(post("/sample/social/bind/two-phase-bind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bindRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("LOGGED_IN"))
                .andExpect(jsonPath("$.data.userId").value("user_bind_target_01"));
    }

    @Test
    @DisplayName("13. [AccountBind] /sample/social/bind/direct-bind/{appKey}: 个人中心已登录态直接绑定第三方账号")
    void test13_AccountBind_DirectBind() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"openid\":\"wx_direct_bind_open_123\",\"session_key\":\"sess_direct\"}"));

        SocialDirectBindRequest directBindRequest = SocialDirectBindRequest.builder()
                .userId("user_login_holder")
                .code("wx_direct_js_code")
                .build();

        mockMvc.perform(post("/sample/social/bind/direct-bind/wechat_mp_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(directBindRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.openId").value("wx_direct_bind_open_123"));
    }

    @Test
    @DisplayName("14. [AccountBind] /sample/social/bind/conflict-check/{appKey}: 跨号抢占防卫拦截 (Conflict Check)")
    void test14_AccountBind_ConflictCheck() throws Exception {
        // 模拟第三方 openId 为 wx_open_bound_1001，该账号已在 SampleSocialUserDetailsService 中被 user_bound_1001 绑定
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"openid\":\"wx_open_bound_1001\",\"session_key\":\"sess_conflict\"}"));

        // 用户 user_attacker 尝试绑定已被绑定的微信/社交账号
        SocialDirectBindRequest conflictRequest = SocialDirectBindRequest.builder()
                .userId("user_attacker")
                .code("mock_conflict_code")
                .build();

        mockMvc.perform(post("/sample/social/bind/conflict-check/wechat_mp_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SocialResultCode.SOCIAL_ALREADY_BOUND.getCode()))
                .andExpect(jsonPath("$.msg").value(containsString("该第三方社交账号已被其他本地用户绑定")));
    }

    @Test
    @DisplayName("15. [AccountBind] 孤儿防卫解绑与已绑定列表查询: /unbind 与 /bindings")
    void test15_AccountBind_UnbindAndListBindings() throws Exception {
        // 15a. 孤儿账号解绑拦截
        SocialUnbindRequest unbindOrphan = SocialUnbindRequest.builder()
                .userId("user_orphan_2002")
                .build();

        mockMvc.perform(post("/sample/social/bind/unbind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unbindOrphan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SocialResultCode.SOCIAL_UNBIND_REJECTED.getCode()));

        // 15b. 查询 user_bound_1001 已绑定社交账号列表
        mockMvc.perform(get("/sample/social/bind/bindings")
                        .param("userId", "user_bound_1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    // ==================== 五、业务领域 4: SocialMultiTenantConfigController 验证 ====================

    @Test
    @DisplayName("16. [MultiTenant] /sample/social/tenant/app-config: SaaS 多租户动态应用配置提取与切源")
    void test16_MultiTenant_DynamicAppConfig() throws Exception {
        // 16a. 提取通过 SocialAppConfigProvider 动态生成的 tenant_saas_app
        mockMvc.perform(get("/sample/social/tenant/app-config")
                        .param("appKey", "tenant_saas_app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.appKey").value("tenant_saas_app"))
                .andExpect(jsonPath("$.data.clientId").value("dynamic_saas_client_id"));

        // 16b. 结合当前请求租户 tenant_saas_01 动态切源
        mockMvc.perform(get("/sample/social/tenant/app-config")
                        .param("appKey", "saas_github")
                        .param("tenantId", "tenant_saas_01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.appKey").value("saas_github"))
                .andExpect(jsonPath("$.data.clientId").value("tenant_01_client_id"));
    }

    @Test
    @DisplayName("17. [MultiTenant] /sample/social/tenant/verify-tenant-callback: 回调中多租户上下文自动恢复验证")
    void test17_MultiTenant_VerifyTenantCallback() throws Exception {
        StateContext context = StateContext.builder()
                .tenantId("tenant_restored_888")
                .redirectUrl("https://ateng.example.com/welcome")
                .build();
        String authorizeUrl = socialAuthTemplate.getAuthorizeUrl("github_app", context);
        String state = extractQueryParam(authorizeUrl, "state");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gh_token_tenant\",\"token_type\":\"bearer\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":1001,\"login\":\"ateng_normal\",\"name\":\"阿腾测试用户\"}"));

        mockMvc.perform(get("/sample/social/tenant/verify-tenant-callback")
                        .param("appKey", "github_app")
                        .param("code", "mock_tenant_code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("tenant_restored_888"));
    }

    @Test
    @DisplayName("18. [MultiTenant] /sample/social/tenant/audit-events: 领域事件四件套审计流水查询")
    void test18_MultiTenant_AuditEventsQuery() throws Exception {
        // 先触发一次正常解绑产生审计事件
        SocialUnbindRequest successUnbind = SocialUnbindRequest.builder()
                .userId("user_bound_1001")
                .build();

        mockMvc.perform(post("/sample/social/bind/unbind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(successUnbind)))
                .andExpect(status().isOk());

        // 查询审计事件记录
        mockMvc.perform(get("/sample/social/tenant/audit-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].eventType").value("UNBOUND"))
                .andExpect(jsonPath("$.data[0].userId").value("user_bound_1001"))
                .andExpect(jsonPath("$.data[0].appKey").value("github_app"));
    }

    private String extractQueryParam(String url, String paramName) {
        int queryIdx = url.indexOf('?');
        if (queryIdx < 0) {
            return null;
        }
        String query = url.substring(queryIdx + 1);
        String[] pairs = query.split("[&?]");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length >= 2 && kv[0].equals(paramName)) {
                return kv[1];
            }
        }
        return null;
    }
}
