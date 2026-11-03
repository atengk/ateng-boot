package io.github.atengk.boot.sample.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.social.listener.SampleSocialEventListener;
import io.github.atengk.boot.sample.social.service.SampleSocialScenarioService;
import io.github.atengk.boot.sample.social.service.SampleSocialUserDetailsService;
import io.github.atengk.boot.social.code.SocialResultCode;
import io.github.atengk.boot.social.http.SpringRestClientHttpImpl;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialBindRequest;
import io.github.atengk.boot.social.model.SocialDirectLoginRequest;
import io.github.atengk.boot.social.model.SocialUnbindRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 社交登录自验样例工程端到端集成测试
 * <p>
 * 依托 MockWebServer 模拟第三方 OAuth2 与微信小程序服务端接口，
 * 验证 Web 扫码授权、回调换码、两阶段防重放绑定、小程序直传登录、孤儿死号防卫及领域事件总线。
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
    }

    @Test
    @DisplayName("1. 端到端验证 /render 端点：生成携带防伪 State 与业务深链的第三方授权地址")
    void test1_RenderAuthorizeUrl() throws Exception {
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
    @DisplayName("2. 端到端验证 Web 回调换码（已绑定用户）：成功签发 Sa-Token 会话与用户信息")
    void test2_WebOAuth2LoginBoundUser() throws Exception {
        // 1. 生成合法授权地址与防伪 State
        String authorizeUrl = socialAuthTemplate.getAuthorizeUrl("github_app");
        String state = extractQueryParam(authorizeUrl, "state");

        // 2. 模拟 GitHub 平台返回 Token 与用户画像（已在 SampleSocialUserDetailsService 中绑定为 user_bound_1001）
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gh_token_1001\",\"token_type\":\"bearer\"}"));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":1001,\"login\":\"ateng_normal\",\"name\":\"阿腾测试用户\"}"));

        // 3. 模拟第三方回调发起 GET 请求
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
    @DisplayName("3. 端到端验证两阶段绑定流与阅后即焚防重放机制")
    void test3_WebOAuth2TwoPhaseBindFlowAndReplayDefense() throws Exception {
        // 1. 生成防伪 State
        String authorizeUrl = socialAuthTemplate.getAuthorizeUrl("github_app");
        String state = extractQueryParam(authorizeUrl, "state");

        // 2. 模拟 GitHub 返回未绑定的全新用户 (openId: 3003)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gh_token_3003\",\"token_type\":\"bearer\"}"));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":3003,\"login\":\"new_registered_user\",\"name\":\"新注册用户\"}"));

        // 3. 回调换码 -> 预期状态为 REQUIRE_BIND 并签发 bindTicket
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

        // 4. 发起两阶段绑定：关联到系统已有或新注册用户 user_brand_new_555
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

        // 5. 对抗性防重放验证：再次消费同一个已销毁的 bindTicket 必须被拦截阻断
        mockMvc.perform(post("/auth/social/bind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bindRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SocialResultCode.SOCIAL_BIND_TICKET_INVALID.getCode()));
    }

    @Test
    @DisplayName("4. 端到端验证微信小程序直传换码认证")
    void test4_MiniProgramDirectLogin() throws Exception {
        // 1. 模拟微信小程序 code2Session 响应
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"openid\":\"wx_open_mini_888\",\"session_key\":\"sess_888\"}"));

        // 2. 发起小程序直传换码
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
    @DisplayName("5. 端到端验证孤儿死号前置安全防卫：拦截无备用凭据的账号解绑并禁止发布事件")
    void test5_UnbindOrphanAccountRejection() throws Exception {
        // user_orphan_2002 未设密码且未绑手机号，社交账号为唯一登录凭据
        SocialUnbindRequest unbindRequest = SocialUnbindRequest.builder()
                .userId("user_orphan_2002")
                .build();

        mockMvc.perform(post("/auth/social/unbind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unbindRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SocialResultCode.SOCIAL_UNBIND_REJECTED.getCode()))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("防止账号孤儿化")));

        // 验证事件监听器未收到任何解绑事件
        assertThat(eventListener.getUnboundCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("6. 端到端验证正常解绑流程与 SocialUnboundEvent 领域事件消费")
    void test6_UnbindSuccessAndEventListener() throws Exception {
        // user_bound_1001 具备独立密码与手机号，允许安全解绑
        SocialUnbindRequest unbindRequest = SocialUnbindRequest.builder()
                .userId("user_bound_1001")
                .build();

        mockMvc.perform(post("/auth/social/unbind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unbindRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证事件监听器成功捕获并处理解绑事件
        assertThat(eventListener.getUnboundCount()).isEqualTo(1);
        assertThat(eventListener.getLastUnboundEvent()).isNotNull();
        assertThat(eventListener.getLastUnboundEvent().getUserId()).isEqualTo("user_bound_1001");
        assertThat(eventListener.getLastUnboundEvent().getAppKey()).isEqualTo("github_app");
    }

    @Test
    @DisplayName("7. 场景门面服务 SampleSocialScenarioService 编排能力验证")
    void test7_ScenarioServiceDirectInvocation() {
        String url = scenarioService.generateWebAuthorizeUrl("github_app", "https://dest.com", "tenant_a");
        assertThat(url).contains("github.com/login/oauth/authorize");

        // 孤儿账号解绑防御断言
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                scenarioService.unbindUser("user_orphan_2002", "github_app")
        ).isInstanceOf(io.github.atengk.boot.social.exception.SocialUnbindRejectedException.class);
    }

    @Test
    @DisplayName("8. 业务场景定制 Controller 接口 (/sample/social/*) 端到端交互验证")
    void test8_CustomSampleSocialControllerEndpoints() throws Exception {
        // 1. 验证生成授权链接
        mockMvc.perform(get("/sample/social/render/github_app")
                        .param("redirectUrl", "https://dest.example.com")
                        .param("tenantId", "tenant_xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("github.com/login/oauth/authorize")));

        // 2. 验证解绑孤儿账号拦截输出友好 Result 响应
        mockMvc.perform(post("/sample/social/unbind/github_app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user_orphan_2002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(7002))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("防止账号孤儿化")));
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
