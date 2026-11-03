package io.github.atengk.boot.social.template;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.social.cache.InMemorySocialAuthStateCache;
import io.github.atengk.boot.social.cache.SocialAuthStateCache;
import io.github.atengk.boot.social.code.SocialResultCode;
import io.github.atengk.boot.social.event.SocialUnboundEvent;
import io.github.atengk.boot.social.exception.SocialAuthException;
import io.github.atengk.boot.social.exception.SocialException;
import io.github.atengk.boot.social.exception.SocialStateExpiredException;
import io.github.atengk.boot.social.exception.SocialUnbindRejectedException;
import io.github.atengk.boot.social.factory.SocialAppFactory;
import io.github.atengk.boot.social.http.AnduinHttpBridge;
import io.github.atengk.boot.social.http.SpringRestClientHttpImpl;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialAuthState;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialUser;
import io.github.atengk.boot.social.model.StateContext;
import io.github.atengk.boot.social.properties.AtengSocialProperties;
import io.github.atengk.boot.social.properties.SocialAppItemProperties;
import io.github.atengk.boot.social.session.SaTokenSocialSessionManager;
import io.github.atengk.boot.social.session.SocialSessionManager;
import io.github.atengk.boot.social.spi.SocialUserDetailsService;
import io.github.atengk.boot.social.test.MockSaTokenTestHelper;
import io.github.atengk.boot.social.ticket.BindTicketStore;
import io.github.atengk.boot.social.ticket.InMemoryBindTicketStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.dromara.anduin.model.AuthCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 社交登录核心门面 SocialAuthTemplate 全真离线单元测试
 * 对接 MockWebServer 模拟第三方 OAuth2 与小程序换码，验证已绑定签发会话与未绑定签发 BindTicket
 *
 * @author Ateng
 * @since 2026-11-03
 */
class SocialAuthTemplateTest {

    private MockWebServer mockWebServer;
    private SpringRestClientHttpImpl httpAdapter;
    private SocialAuthStateCache stateCache;
    private BindTicketStore bindTicketStore;
    private SocialUserDetailsService userDetailsService;
    private SocialSessionManager sessionManager;
    private SocialAppFactory appFactory;
    private ApplicationEventPublisher eventPublisher;
    private SocialAuthTemplate authTemplate;

    @BeforeEach
    void setUp() throws IOException {
        MockSaTokenTestHelper.setUp();

        // 1. 启动本地 MockWebServer
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // 2. 初始化网络适配器并将所有请求重定向至本地 MockWebServer
        httpAdapter = new SpringRestClientHttpImpl(Duration.ofSeconds(3), Duration.ofSeconds(3), null);
        httpAdapter.setUrlRewriter(originalUrl -> {
            try {
                URI uri = URI.create(originalUrl);
                String pathAndQuery = (uri.getRawPath() != null ? uri.getRawPath() : "")
                        + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
                return mockWebServer.url(pathAndQuery).toString();
            } catch (Exception ex) {
                return originalUrl;
            }
        });
        AnduinHttpBridge.setAdapter(httpAdapter);

        // 3. 配置应用属性：包含一个 GitHub Web 应用和一个微信小程序应用
        AtengSocialProperties properties = new AtengSocialProperties();

        SocialAppItemProperties githubProps = new SocialAppItemProperties();
        githubProps.setSource("GITHUB");
        githubProps.setClientId("mock_client_id_github");
        githubProps.setClientSecret("mock_client_secret_github");
        githubProps.setRedirectUri("https://ateng.example.com/oauth/callback/github");
        properties.getApps().put("github_app", githubProps);

        SocialAppItemProperties wechatMpProps = new SocialAppItemProperties();
        wechatMpProps.setSource("WECHAT_MINI_PROGRAM");
        wechatMpProps.setClientId("mock_appid_wechat_mp");
        wechatMpProps.setClientSecret("mock_secret_wechat_mp");
        wechatMpProps.setIgnoreCheckState(true);
        wechatMpProps.setIgnoreCheckRedirectUri(true);
        properties.getApps().put("wechat_mp_app", wechatMpProps);

        // 4. 组装缓存与会话管理器
        stateCache = new InMemorySocialAuthStateCache();
        bindTicketStore = new InMemoryBindTicketStore();
        userDetailsService = mock(SocialUserDetailsService.class);
        doCallRealMethod().when(userDetailsService).bindSocialUser(any(), any(), any());
        sessionManager = new SaTokenSocialSessionManager();
        eventPublisher = mock(ApplicationEventPublisher.class);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("socialUserDetailsService", userDetailsService);
        beanFactory.addBean("socialAuthStateCache", stateCache);

        appFactory = new SocialAppFactory(properties, null, beanFactory.getBeanProvider(org.dromara.anduin.cache.AuthStateCache.class), httpAdapter);
        authTemplate = new SocialAuthTemplate(appFactory, stateCache, bindTicketStore, beanFactory.getBeanProvider(SocialUserDetailsService.class), sessionManager, eventPublisher);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
        AnduinHttpBridge.reset();
        MockSaTokenTestHelper.tearDown();
    }

    @Test
    @DisplayName("1. getAuthorizeUrl: 渲染携带防伪 State 的第三方授权 URL 并缓存 StateContext")
    void testGetAuthorizeUrl() {
        StateContext context = StateContext.builder()
                .tenantId("tenant_dev")
                .redirectUrl("/dashboard")
                .extraParams(Map.of("channel", "web"))
                .build();

        String authorizeUrl = authTemplate.getAuthorizeUrl("github_app", context);
        // 提取 state
        String state = extractQueryParam(authorizeUrl, "state");
        assertThat(state).isNotBlank();

        // 验证 State 缓存中存在该 context
        StateContext cachedContext = stateCache.getContext(state);
        assertThat(cachedContext).isNotNull();
        assertThat(cachedContext.getTenantId()).isEqualTo("tenant_dev");
        assertThat(cachedContext.getRedirectUrl()).isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("2. getAuthorizeUrl: appKey 为空时防御性快速失败")
    void testGetAuthorizeUrlValidation() {
        assertThatThrownBy(() -> authTemplate.getAuthorizeUrl(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("应用标识 [appKey] 不能为空");

        assertThatThrownBy(() -> authTemplate.getAuthorizeUrl("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("3. login: Web 回调换码认证（已绑定本地用户 -> 签发 Sa-Token 会话）")
    void testLoginWithBoundUser() {
        // 1. 先生成防伪 state 并预置上下文
        StateContext context = StateContext.builder()
                .tenantId("tenant_001")
                .redirectUrl("/user/home")
                .build();
        String state = stateCache.generateState(context);

        // 2. 预设 MockWebServer 模拟 GitHub access_token 与 userinfo 响应
        String tokenJson = "{\"access_token\":\"gho_mock_token_123\",\"token_type\":\"bearer\",\"scope\":\"user\"}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(tokenJson));

        String userInfoJson = "{\"id\":998877,\"login\":\"ateng_dev\",\"name\":\"阿腾\",\"avatar_url\":\"https://ateng.example.com/avatar.png\",\"email\":\"ateng@example.com\"}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(userInfoJson));

        // 3. 模拟持久化 SPI 识别到已绑定本地用户 user_999
        when(userDetailsService.loadUserIdBySocialUser(eq("github_app"), any(SocialUser.class)))
                .thenReturn("user_999");

        // 4. 发起回调认证
        AuthCallback callback = AuthCallback.builder()
                .code("mock_github_code_abc")
                .state(state)
                .build();

        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType("PC")
                .build();

        SocialAuthResult result = authTemplate.login("github_app", callback, options);

        // 5. 断言认证结果与 Sa-Token 登录态
        assertThat(result).isNotNull();
        assertThat(result.isLoggedIn()).isTrue();
        assertThat(result.isRequireBind()).isFalse();
        assertThat(result.getState()).isEqualTo(SocialAuthState.LOGGED_IN);
        assertThat(result.getUserId()).isEqualTo("user_999");
        assertThat(result.getTokenInfo()).isNotNull();
        assertThat(StpUtil.isLogin()).isTrue();
        assertThat(result.getStateContext()).isNotNull();
        assertThat(result.getStateContext().getTenantId()).isEqualTo("tenant_001");

        // 验证用户标准化画像
        SocialUser socialUser = result.getSocialUser();
        assertThat(socialUser).isNotNull();
        assertThat(socialUser.getSource()).isEqualTo("GITHUB");
        assertThat(socialUser.getUsername()).isEqualTo("ateng_dev");
        assertThat(socialUser.getNickname()).isEqualTo("阿腾");

        // 6. 验证防伪 state 已阅后即焚原子删除（防重放）
        assertThat(stateCache.containsKey(state)).isFalse();

        // 再次使用同一回调重放，应当拒绝
        assertThatThrownBy(() -> authTemplate.login("github_app", callback, options))
                .isInstanceOf(SocialException.class)
                .hasMessageContaining("OAuth2 状态码 State 非法、已过期或已被重复消费");
    }

    @Test
    @DisplayName("4. login: Web 回调换码认证（未绑定本地用户 -> 签发两阶段 BindTicket）")
    void testLoginWithUnboundUser() {
        // 1. 生成防伪 state
        StateContext context = StateContext.builder().tenantId("tenant_new").build();
        String state = stateCache.generateState(context);

        // 2. 预设 Mock 响应
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gho_mock_token_456\",\"token_type\":\"bearer\"}"));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":112233,\"login\":\"new_comer\",\"name\":\"新用户\",\"email\":\"new@example.com\"}"));

        // 3. 模拟持久化 SPI 未查到绑定记录
        when(userDetailsService.loadUserIdBySocialUser(eq("github_app"), any(SocialUser.class)))
                .thenReturn(null);

        AuthCallback callback = AuthCallback.builder()
                .code("mock_github_code_xyz")
                .state(state)
                .build();

        // 4. 执行登录认证
        SocialAuthResult result = authTemplate.login("github_app", callback);

        // 5. 断言转入两阶段绑定流程
        assertThat(result).isNotNull();
        assertThat(result.isLoggedIn()).isFalse();
        assertThat(result.isRequireBind()).isTrue();
        assertThat(result.getState()).isEqualTo(SocialAuthState.REQUIRE_BIND);
        assertThat(result.getUserId()).isNull();
        assertThat(result.getTokenInfo()).isNull();
        assertThat(result.getBindTicket()).isNotBlank();

        // 验证 BindTicketStore 中存在暂存的画像
        SocialUser cachedUser = bindTicketStore.get(result.getBindTicket());
        assertThat(cachedUser).isNotNull();
        assertThat(cachedUser.getUsername()).isEqualTo("new_comer");
    }

    @Test
    @DisplayName("5. login: State 缺失或伪造时的对抗性拦截防卫")
    void testLoginStateAdversarialDefense() {
        // 场景 A: 回调中缺失 state
        AuthCallback noStateCallback = AuthCallback.builder().code("abc").build();
        assertThatThrownBy(() -> authTemplate.login("github_app", noStateCallback))
                .isInstanceOf(SocialException.class)
                .hasMessageContaining("未携带防伪状态码");

        // 场景 B: 伪造的不存在 state
        AuthCallback fakeStateCallback = AuthCallback.builder().code("abc").state("fake_tampered_state").build();
        assertThatThrownBy(() -> authTemplate.login("github_app", fakeStateCallback))
                .isInstanceOf(SocialException.class)
                .hasMessageContaining("OAuth2 状态码 State 非法、已过期或已被重复消费");
    }

    @Test
    @DisplayName("6. directLogin: 微信小程序 (wx.login code2Session) 直传换码（已绑定用户）")
    void testDirectLoginWechatMiniProgramBoundUser() {
        // 1. 模拟微信小程序 code2Session 响应
        String code2SessionJson = "{\"openid\":\"wx_mock_open_777\",\"unionid\":\"wx_mock_union_888\",\"session_key\":\"sess_key_999\"}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(code2SessionJson));

        // 2. 模拟本地用户系统根据 unionid 匹配到用户
        when(userDetailsService.loadUserIdBySocialUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn("user_wx_bound_001");

        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType("MINI_PROGRAM")
                .extraData(Map.of("clientVersion", "1.0.0"))
                .build();

        // 3. 执行直传登录
        SocialAuthResult result = authTemplate.directLogin("wechat_mp_app", "mock_wechat_js_code_123", options);

        // 4. 断言结果
        assertThat(result).isNotNull();
        assertThat(result.isLoggedIn()).isTrue();
        assertThat(result.getUserId()).isEqualTo("user_wx_bound_001");
        assertThat(result.getTokenInfo()).isNotNull();

        SocialUser socialUser = result.getSocialUser();
        assertThat(socialUser).isNotNull();
        assertThat(socialUser.getOpenId()).isEqualTo("wx_mock_open_777");
        assertThat(socialUser.getUnionId()).isEqualTo("wx_mock_union_888");
    }

    @Test
    @DisplayName("7. directLogin: 微信小程序直传换码（未绑定用户 -> 签发 BindTicket）")
    void testDirectLoginWechatMiniProgramUnboundUser() {
        String code2SessionJson = "{\"openid\":\"wx_new_open_111\",\"session_key\":\"sess_111\"}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(code2SessionJson));

        when(userDetailsService.loadUserIdBySocialUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn(null);

        SocialAuthResult result = authTemplate.directLogin("wechat_mp_app", "mock_new_js_code");

        assertThat(result).isNotNull();
        assertThat(result.isRequireBind()).isTrue();
        assertThat(result.getBindTicket()).isNotBlank();
        assertThat(result.getSocialUser().getOpenId()).isEqualTo("wx_new_open_111");
    }

    @Test
    @DisplayName("8. directLogin: 凭证为空时快速失败")
    void testDirectLoginValidation() {
        assertThatThrownBy(() -> authTemplate.directLogin("wechat_mp_app", (String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("授权凭证 [code] 不能为空");

        assertThatThrownBy(() -> authTemplate.directLogin("wechat_mp_app", "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> authTemplate.directLogin("wechat_mp_app", (AuthCallback) null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据回调参数 [callback] 不能为空");
    }

    @Test
    @DisplayName("9. getDirectSocialUser: 纯资料直传换码获取，不产生系统会话")
    void testGetDirectSocialUser() {
        String code2SessionJson = "{\"openid\":\"wx_profile_open\",\"session_key\":\"sess_profile\"}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(code2SessionJson));

        SocialUser socialUser = authTemplate.getDirectSocialUser("wechat_mp_app", "code_for_profile");
        assertThat(socialUser).isNotNull();
        assertThat(socialUser.getOpenId()).isEqualTo("wx_profile_open");
        assertThat(StpUtil.isLogin()).isFalse();
    }

    @Test
    @DisplayName("10. bind: 消费 BindTicket 成功绑定本地账号并签发 Sa-Token 登录会话")
    void testBindSuccess() {
        // 1. 预置一个待绑定的第三方画像并生成 BindTicket
        SocialUser pendingUser = SocialUser.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_user_888")
                .username("dev_ateng")
                .nickname("阿腾工程师")
                .build();
        String bindTicket = bindTicketStore.createTicket(pendingUser);

        // 2. 验证票据存在
        assertThat(bindTicketStore.contains(bindTicket)).isTrue();

        // 3. 执行绑定
        SocialLoginOptions options = SocialLoginOptions.builder()
                .deviceType("PC")
                .build();
        SocialAuthResult result = authTemplate.bind(bindTicket, "local_user_777", options);

        // 4. 断言认证结果与登录状态
        assertThat(result).isNotNull();
        assertThat(result.isLoggedIn()).isTrue();
        assertThat(result.isRequireBind()).isFalse();
        assertThat(result.getState()).isEqualTo(SocialAuthState.LOGGED_IN);
        assertThat(result.getUserId()).isEqualTo("local_user_777");
        assertThat(result.getTokenInfo()).isNotNull();
        assertThat(result.getSocialUser()).isNotNull();
        assertThat(result.getSocialUser().getOpenId()).isEqualTo("gh_user_888");

        // 验证 Sa-Token 会话已确立
        assertThat(StpUtil.isLogin()).isTrue();
        assertThat(StpUtil.getLoginIdAsString()).isEqualTo("local_user_777");

        // 验证持久化 SPI 被正确调用
        verify(userDetailsService).bindSocialUser(eq("local_user_777"), eq("github_app"), any(SocialUser.class));

        // 5. 验证 BindTicket 已被原子物理销毁（阅后即焚）
        assertThat(bindTicketStore.contains(bindTicket)).isFalse();
        assertThat(bindTicketStore.get(bindTicket)).isNull();
    }

    @Test
    @DisplayName("11. bind: 阅后即焚防重放防御（第二次重复消费立即强拦截阻断）")
    void testBindBurnAfterReadingAndReplayAttackDefense() {
        SocialUser pendingUser = SocialUser.builder()
                .appKey("wechat_mp_app")
                .source("WECHAT_MP")
                .openId("wx_unique_666")
                .build();
        String bindTicket = bindTicketStore.createTicket(pendingUser);

        // 首次消费绑定成功
        SocialAuthResult firstResult = authTemplate.bind(bindTicket, "user_first");
        assertThat(firstResult.isLoggedIn()).isTrue();

        // 再次使用同一 BindTicket 发起重放绑定，强拦截并抛出异常
        assertThatThrownBy(() -> authTemplate.bind(bindTicket, "user_second"))
                .isInstanceOf(SocialException.class)
                .hasMessageContaining("绑定凭证 BindTicket 非法、已过期或已被重复消费");
    }

    @Test
    @DisplayName("12. bind: 伪造或过期的 BindTicket 对抗性防卫拦截")
    void testBindExpiredOrFakeTicket() {
        assertThatThrownBy(() -> authTemplate.bind("fake_non_exist_ticket_123", "user_123"))
                .isInstanceOf(SocialException.class)
                .hasMessageContaining("绑定凭证 BindTicket 非法、已过期或已被重复消费");
    }

    @Test
    @DisplayName("13. bind: 业务绑定冲突异常正确穿透向外抛出且票据已被安全销毁")
    void testBindBusinessExceptionPropagationAndTicketConsumed() {
        SocialUser pendingUser = SocialUser.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_conflict_999")
                .build();
        String bindTicket = bindTicketStore.createTicket(pendingUser);

        // 模拟业务 SPI 检测到第三方账号已被其他账号绑定，抛出业务冲突异常
        doThrow(new IllegalStateException("该 GitHub 账号已被其他本地用户绑定，禁止重复关联"))
                .when(userDetailsService).bind(eq("local_user_fail"), eq("github_app"), any(SocialUser.class));

        // 调用 bind 应当穿透抛出该异常
        assertThatThrownBy(() -> authTemplate.bind(bindTicket, "local_user_fail"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("该 GitHub 账号已被其他本地用户绑定，禁止重复关联");

        // 关键断言：即使绑定逻辑发生异常，该凭据也已被原子销毁，不可被二次利用重放
        assertThat(bindTicketStore.contains(bindTicket)).isFalse();
    }

    @Test
    @DisplayName("14. bind: 参数非法时前置快速失败")
    void testBindValidation() {
        assertThatThrownBy(() -> authTemplate.bind(null, "user_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("两阶段绑定票据 [bindTicket] 不能为空");

        assertThatThrownBy(() -> authTemplate.bind("   ", "user_1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> authTemplate.bind("ticket_ok", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("本地用户标识 [userId] 不能为空");

        assertThatThrownBy(() -> authTemplate.bind("ticket_ok", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("15. unbind: 正常解除绑定并原子广播 SocialUnboundEvent 领域事件")
    void testUnbindSuccess() {
        when(userDetailsService.canUnbind("user_1001", "github_app")).thenReturn(true);

        authTemplate.unbind("user_1001", "github_app");

        verify(userDetailsService).canUnbind("user_1001", "github_app");
        verify(userDetailsService).unbind("user_1001", "github_app");

        ArgumentCaptor<SocialUnboundEvent> eventCaptor = ArgumentCaptor.forClass(SocialUnboundEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        SocialUnboundEvent event = eventCaptor.getValue();
        assertThat(event).isNotNull();
        assertThat(event.getUserId()).isEqualTo("user_1001");
        assertThat(event.getAppKey()).isEqualTo("github_app");
        assertThat(event.getUnbindTime()).isNotNull();
        assertThat(event.getSource()).isSameAs(authTemplate);
    }

    @Test
    @DisplayName("16. unbind: 孤儿死号安全防卫拦截（canUnbind 为 false 时刚性阻断）")
    void testUnbindRejectedByOrphanGuard() {
        when(userDetailsService.canUnbind("user_orphan", "github_app")).thenReturn(false);

        assertThatThrownBy(() -> authTemplate.unbind("user_orphan", "github_app"))
                .isInstanceOf(SocialUnbindRejectedException.class)
                .hasMessageContaining("user_orphan")
                .hasMessageContaining("github_app")
                .hasMessageContaining("未绑定手机号或未设独立密码，防止账号孤儿化");

        verify(userDetailsService).canUnbind("user_orphan", "github_app");
        verify(userDetailsService, never()).unbind(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("17. unbind: 入参格式前置快速失败校验")
    void testUnbindValidation() {
        assertThatThrownBy(() -> authTemplate.unbind(null, "github_app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("本地用户标识 [userId] 不能为空");

        assertThatThrownBy(() -> authTemplate.unbind("   ", "github_app"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> authTemplate.unbind("user_1001", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("应用标识 [appKey] 不能为空");

        assertThatThrownBy(() -> authTemplate.unbind("user_1001", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("18. unbind: canUnbind 异常时保留因果链并向外抛出")
    void testUnbindCanUnbindThrowsExceptionPreservingCause() {
        RuntimeException rootCause = new RuntimeException("DB Connection Timeout");
        when(userDetailsService.canUnbind("user_err", "github_app")).thenThrow(rootCause);

        assertThatThrownBy(() -> authTemplate.unbind("user_err", "github_app"))
                .isInstanceOf(SocialException.class)
                .hasCause(rootCause)
                .hasMessageContaining("孤儿账号解绑核验执行异常");

        verify(userDetailsService, never()).unbind(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("19. unbind: 持久化 SPI 解绑异常时保留因果链且不广播事件")
    void testUnbindPersistenceThrowsExceptionPreservingCause() {
        when(userDetailsService.canUnbind("user_err2", "github_app")).thenReturn(true);
        RuntimeException rootCause = new RuntimeException("Deadlock detected during unbind");
        doThrow(rootCause).when(userDetailsService).unbind("user_err2", "github_app");

        assertThatThrownBy(() -> authTemplate.unbind("user_err2", "github_app"))
                .isInstanceOf(SocialException.class)
                .hasCause(rootCause)
                .hasMessageContaining("解除第三方账号绑定异常");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("20. unbind: 未提供 SocialUserDetailsService SPI 时刚性阻断并抛出异常")
    void testUnbindWithoutUserDetailsService() {
        StaticListableBeanFactory emptyFactory = new StaticListableBeanFactory();
        SocialAuthTemplate standaloneTemplate = new SocialAuthTemplate(
                appFactory,
                stateCache,
                bindTicketStore,
                emptyFactory.getBeanProvider(SocialUserDetailsService.class),
                sessionManager,
                eventPublisher
        );

        assertThatThrownBy(() -> standaloneTemplate.unbind("user_standalone", "github_app"))
                .isInstanceOf(SocialException.class)
                .hasMessageContaining("未配置 SocialUserDetailsService");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("21. setApplicationEventPublisher: 验证 ApplicationEventPublisherAware 接口装配能力")
    void testSetApplicationEventPublisherAware() {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        SocialUserDetailsService mockDetails = mock(SocialUserDetailsService.class);
        when(mockDetails.canUnbind("user_aware", "github_app")).thenReturn(true);
        factory.addBean("details", mockDetails);

        SocialAuthTemplate template = new SocialAuthTemplate(
                appFactory,
                stateCache,
                bindTicketStore,
                factory.getBeanProvider(SocialUserDetailsService.class),
                sessionManager
        );

        ApplicationEventPublisher customPublisher = mock(ApplicationEventPublisher.class);
        template.setApplicationEventPublisher(customPublisher);

        template.unbind("user_aware", "github_app");
        verify(customPublisher).publishEvent(any(SocialUnboundEvent.class));
    }

    @Test
    @DisplayName("22. bind: 跨应用标识篡改 BindTicket 时刚性拦截阻断")
    void testBindCrossAppKeyMismatchDefense() {
        SocialUser pendingUser = SocialUser.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_user_tamper")
                .build();
        String bindTicket = bindTicketStore.createTicket(pendingUser);

        assertThatThrownBy(() -> authTemplate.bind("wechat_mp_app", bindTicket, "local_user_1", null))
                .isInstanceOf(SocialException.class)
                .hasMessageContaining("绑定凭据关联的应用标识与当前参数不匹配");

        // 验证因非法篡改被拒绝后，票据已被原子销毁（阅后即焚）
        assertThat(bindTicketStore.contains(bindTicket)).isFalse();
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
