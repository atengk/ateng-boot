package io.github.atengk.boot.social.template;

import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.social.cache.InMemorySocialAuthStateCache;
import io.github.atengk.boot.social.cache.SocialAuthStateCache;
import io.github.atengk.boot.social.code.SocialResultCode;
import io.github.atengk.boot.social.event.SocialAuthenticatedEvent;
import io.github.atengk.boot.social.event.SocialBindTicketIssuedEvent;
import io.github.atengk.boot.social.event.SocialBoundEvent;
import io.github.atengk.boot.social.event.SocialUnboundEvent;
import io.github.atengk.boot.social.exception.SocialAlreadyBoundException;
import io.github.atengk.boot.social.exception.SocialAuthException;
import io.github.atengk.boot.social.exception.SocialException;
import io.github.atengk.boot.social.exception.SocialRedirectUriInvalidException;
import io.github.atengk.boot.social.exception.SocialStateExpiredException;
import io.github.atengk.boot.social.exception.SocialUnbindRejectedException;
import io.github.atengk.boot.social.factory.SocialAppFactory;
import io.github.atengk.boot.social.http.AnduinHttpBridge;
import io.github.atengk.boot.social.http.SpringRestClientHttpImpl;
import io.github.atengk.boot.social.model.SocialAuthResult;
import io.github.atengk.boot.social.model.SocialAuthState;
import io.github.atengk.boot.social.model.SocialBoundAccount;
import io.github.atengk.boot.social.model.SocialLoginOptions;
import io.github.atengk.boot.social.model.SocialToken;
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
import org.dromara.anduin.model.AuthToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

        SocialAppItemProperties baiduProps = new SocialAppItemProperties();
        baiduProps.setSource("BAIDU");
        baiduProps.setClientId("mock_client_id_baidu");
        baiduProps.setClientSecret("mock_client_secret_baidu");
        baiduProps.setRedirectUri("https://ateng.example.com/oauth/callback/baidu");
        properties.getApps().put("baidu_app", baiduProps);

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
        authTemplate = new SocialAuthTemplate(appFactory, stateCache, bindTicketStore, beanFactory.getBeanProvider(SocialUserDetailsService.class), sessionManager, properties, eventPublisher);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
        AnduinHttpBridge.reset();
        MockSaTokenTestHelper.tearDown();
        TenantContextHolder.clear();
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

    @Test
    @DisplayName("23. getAuthorizeUrl: 校验开放重定向防御机制")
    void testOpenRedirectProtectionInGetAuthorizeUrl() {
        // 伪协议拦截
        StateContext maliciousContext = StateContext.builder()
                .redirectUrl("javascript:alert(1)")
                .build();
        assertThatThrownBy(() -> authTemplate.getAuthorizeUrl("github_app", maliciousContext))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("包含危险伪协议");

        // 协议相对路径拦截
        StateContext protocolRelativeContext = StateContext.builder()
                .redirectUrl("//evil.com/phishing")
                .build();
        assertThatThrownBy(() -> authTemplate.getAuthorizeUrl("github_app", protocolRelativeContext))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("协议相对路径");

        // 站内相对路径安全放行
        StateContext safeRelativeContext = StateContext.builder()
                .redirectUrl("/welcome/user")
                .build();
        String safeUrl = authTemplate.getAuthorizeUrl("github_app", safeRelativeContext);
        assertThat(safeUrl).contains("redirect_uri=");

        // 白名单域名校验拦截与放行
        authTemplate.getProperties().setAllowedRedirectDomains(java.util.List.of("*.example.com", "ateng.com"));

        StateContext nonWhitelistedContext = StateContext.builder()
                .redirectUrl("https://evil.com/landing")
                .build();
        assertThatThrownBy(() -> authTemplate.getAuthorizeUrl("github_app", nonWhitelistedContext))
                .isInstanceOf(SocialRedirectUriInvalidException.class)
                .hasMessageContaining("不在允许的白名单列表中");

        StateContext whitelistedContext = StateContext.builder()
                .redirectUrl("https://sso.example.com/callback")
                .build();
        String allowedUrl = authTemplate.getAuthorizeUrl("github_app", whitelistedContext);
        assertThat(allowedUrl).contains("state=");
    }

    @Test
    @DisplayName("24. getAuthorizeUrl: 自动捕获当前线程 TenantContextHolder 租户标识")
    void testTenantAutoCaptureInGetAuthorizeUrl() {
        TenantContextHolder.setTenantId("tenant_auto_999");

        // 未显式指定 tenantId，自动从当前线程捕获
        StateContext emptyContext = new StateContext();
        String authorizeUrl = authTemplate.getAuthorizeUrl("github_app", emptyContext);
        String state = extractQueryParam(authorizeUrl, "state");
        StateContext cachedContext = stateCache.getContext(state);
        assertThat(cachedContext).isNotNull();
        assertThat(cachedContext.getTenantId()).isEqualTo("tenant_auto_999");

        // 若显式指定了 tenantId，优先使用显式指定的租户
        StateContext explicitContext = StateContext.builder().tenantId("tenant_explicit_111").build();
        String explicitUrl = authTemplate.getAuthorizeUrl("github_app", explicitContext);
        String state2 = extractQueryParam(explicitUrl, "state");
        StateContext cachedContext2 = stateCache.getContext(state2);
        assertThat(cachedContext2).isNotNull();
        assertThat(cachedContext2.getTenantId()).isEqualTo("tenant_explicit_111");
    }

    @Test
    @DisplayName("25. login: 回调阶段自动透传 TenantContextHolder 并在处理完毕后恢复原上下文")
    void testTenantContextBridgedDuringLogin() {
        TenantContextHolder.setTenantId("tenant_base");

        // 构造携带租户标识的 State
        StateContext stateContext = StateContext.builder()
                .tenantId("tenant_callback_888")
                .build();
        String state = stateCache.generateState(stateContext);

        // Mock GitHub token & user info response
        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"access_token\":\"gh_token_tenant_test\",\"token_type\":\"bearer\",\"scope\":\"user\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"id\":998877,\"login\":\"tenant_user\",\"name\":\"Tenant User\",\"avatar_url\":\"https://avatar\"}"));

        // 在 userDetailsService 中核验执行时当前线程的 TenantId 是否为 tenant_callback_888
        when(userDetailsService.loadUserIdBySocialUser(eq("github_app"), any(SocialUser.class)))
                .thenAnswer(invocation -> {
                    assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_callback_888");
                    return "local_user_tenant_888";
                });

        AuthCallback callback = AuthCallback.builder().code("test_code_tenant").state(state).build();
        SocialAuthResult result = authTemplate.login("github_app", callback);

        assertThat(result.getState()).isEqualTo(SocialAuthState.LOGGED_IN);
        assertThat(result.getUserId()).isEqualTo("local_user_tenant_888");

        // 验证执行完毕后无条件恢复为原来的 tenant_base
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_base");
    }

    @Test
    @DisplayName("26. login: 持久化 SPI 发生异常时依然确定性恢复 TenantContextHolder")
    void testTenantContextRestoredOnLoginException() {
        TenantContextHolder.setTenantId("tenant_base");

        StateContext stateContext = StateContext.builder()
                .tenantId("tenant_callback_999")
                .build();
        String state = stateCache.generateState(stateContext);

        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"access_token\":\"gh_token_tenant_err\",\"token_type\":\"bearer\",\"scope\":\"user\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"id\":998899,\"login\":\"tenant_err_user\",\"name\":\"Tenant Err\",\"avatar_url\":\"https://avatar\"}"));

        when(userDetailsService.loadUserIdBySocialUser(eq("github_app"), any(SocialUser.class)))
                .thenAnswer(invocation -> {
                    assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_callback_999");
                    throw new RuntimeException("Simulated SPI Database Exception");
                });

        AuthCallback callback = AuthCallback.builder().code("test_code_err").state(state).build();
        assertThatThrownBy(() -> authTemplate.login("github_app", callback))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated SPI Database Exception");

        // 验证异常发生后 TenantContextHolder 依然确定性恢复为 tenant_base
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant_base");
    }

    @Test
    @DisplayName("27. autoSignUp: 开启全局自动注册且 SPI 返回用户ID时直接完成绑定与登录")
    void testAutoSignUpSuccess() {
        authTemplate.getProperties().setAutoSignUp(true);

        when(userDetailsService.loadUserIdBySocialUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn(null);
        when(userDetailsService.autoRegisterUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn("auto_new_user_100");

        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"openid\":\"wx_auto_open_100\",\"session_key\":\"sess_100\",\"unionid\":\"wx_union_100\"}"));

        SocialAuthResult result = authTemplate.directLogin("wechat_mp_app", "code_auto_100");

        assertThat(result.getState()).isEqualTo(SocialAuthState.LOGGED_IN);
        assertThat(result.getUserId()).isEqualTo("auto_new_user_100");
        assertThat(result.getTokenInfo()).isNotNull();

        // 验证领域事件依次触发：SocialBoundEvent -> SocialAuthenticatedEvent
        ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(2)).publishEvent(eventCaptor.capture());

        boolean hasBoundEvent = eventCaptor.getAllValues().stream()
                .anyMatch(e -> e instanceof SocialBoundEvent bound && bound.getUserId().equals("auto_new_user_100"));
        boolean hasAuthEvent = eventCaptor.getAllValues().stream()
                .anyMatch(e -> e instanceof SocialAuthenticatedEvent auth && auth.getUserId().equals("auto_new_user_100"));

        assertThat(hasBoundEvent).isTrue();
        assertThat(hasAuthEvent).isTrue();
    }

    @Test
    @DisplayName("28. autoSignUp: 开启自动注册但 SPI 返回 null 时平滑回退至两阶段绑定")
    void testAutoSignUpReturnsNullFallbacksToBindTicket() {
        authTemplate.getProperties().setAutoSignUp(true);

        when(userDetailsService.loadUserIdBySocialUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn(null);
        when(userDetailsService.autoRegisterUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn(null); // SPI 拒绝自动开户，要求回退

        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"openid\":\"wx_fallback_open\",\"session_key\":\"sess_fb\"}"));

        SocialAuthResult result = authTemplate.directLogin("wechat_mp_app", "code_fb");

        assertThat(result.getState()).isEqualTo(SocialAuthState.REQUIRE_BIND);
        assertThat(result.getBindTicket()).isNotBlank();

        // 验证广播了脱敏票据事件，未广播绑定事件
        ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        Object published = eventCaptor.getValue();
        assertThat(published).isInstanceOf(SocialBindTicketIssuedEvent.class);
        SocialBindTicketIssuedEvent ticketEvent = (SocialBindTicketIssuedEvent) published;
        assertThat(ticketEvent.getMaskedTicket()).contains("*");
        assertThat(ticketEvent.getAppKey()).isEqualTo("wechat_mp_app");
        assertThat(ticketEvent.getSocialUser().getOpenId()).isEqualTo("wx_fallback_open");
    }

    @Test
    @DisplayName("29. autoSignUp: 默认关闭时未绑定用户直接进入两阶段绑定且不调用 autoRegisterUser")
    void testAutoSignUpDisabledByDefault() {
        authTemplate.getProperties().setAutoSignUp(false);

        when(userDetailsService.loadUserIdBySocialUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn(null);

        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"openid\":\"wx_def_open\",\"session_key\":\"sess_def\"}"));

        SocialAuthResult result = authTemplate.directLogin("wechat_mp_app", "code_def");

        assertThat(result.getState()).isEqualTo(SocialAuthState.REQUIRE_BIND);
        verify(userDetailsService, never()).autoRegisterUser(any(), any());

        ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SocialBindTicketIssuedEvent.class);
    }

    @Test
    @DisplayName("30. autoSignUp: 应用级 autoSignUp 配置覆盖全局配置")
    void testAppLevelAutoSignUpOverridesGlobal() {
        // 全局关闭
        authTemplate.getProperties().setAutoSignUp(false);

        // 应用级为 wechat_mp_app 单独开启
        SocialAppItemProperties mpProps = authTemplate.getProperties().getApps().get("wechat_mp_app");
        mpProps.setAutoSignUp(true);

        when(userDetailsService.loadUserIdBySocialUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn(null);
        when(userDetailsService.autoRegisterUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn("user_app_level_override");

        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"openid\":\"wx_override_open\",\"session_key\":\"sess_ov\"}"));

        SocialAuthResult result = authTemplate.directLogin("wechat_mp_app", "code_ov");

        assertThat(result.getState()).isEqualTo(SocialAuthState.LOGGED_IN);
        assertThat(result.getUserId()).isEqualTo("user_app_level_override");

        // 恢复应用级配置
        mpProps.setAutoSignUp(null);
    }

    @Test
    @DisplayName("31. domainEvents: 验证两阶段绑定 bind 成功触发 SocialBoundEvent 与 SocialAuthenticatedEvent")
    void testDomainEventsInBind() {
        SocialUser pendingUser = SocialUser.builder()
                .openId("open_bind_event")
                .source("GITHUB")
                .appKey("github_app")
                .username("bindUser")
                .build();
        String bindTicket = bindTicketStore.createTicket(pendingUser);

        SocialAuthResult result = authTemplate.bind("github_app", bindTicket, "user_bound_events", null);
        assertThat(result.getState()).isEqualTo(SocialAuthState.LOGGED_IN);

        ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(2)).publishEvent(eventCaptor.capture());

        boolean hasBound = eventCaptor.getAllValues().stream()
                .anyMatch(e -> e instanceof SocialBoundEvent b && b.getUserId().equals("user_bound_events"));
        boolean hasAuth = eventCaptor.getAllValues().stream()
                .anyMatch(e -> e instanceof SocialAuthenticatedEvent a && a.getUserId().equals("user_bound_events"));

        assertThat(hasBound).isTrue();
        assertThat(hasAuth).isTrue();
    }

    @Test
    @DisplayName("32. refreshToken: 参数校验前置卫语句拦截")
    void testRefreshTokenValidation() {
        assertThatThrownBy(() -> authTemplate.refreshToken(null, "some_refresh_token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.refreshToken("   ", "some_refresh_token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.refreshToken("baidu_app", (String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.refreshToken("baidu_app", "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.refreshToken("baidu_app", (AuthToken) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("33. refreshToken: 成功续期并返回标准化 SocialToken")
    void testRefreshTokenSuccess() {
        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"access_token\":\"baidu_new_access_token\",\"expires_in\":2592000,\"refresh_token\":\"baidu_new_refresh_token\",\"session_key\":\"sk_123\",\"session_secret\":\"ss_123\",\"scope\":\"basic\"}"));

        SocialToken token = authTemplate.refreshToken("baidu_app", "baidu_old_refresh");
        assertThat(token).isNotNull();
        assertThat(token.getAccessToken()).isEqualTo("baidu_new_access_token");
        assertThat(token.getRefreshToken()).isEqualTo("baidu_new_refresh_token");
        assertThat(token.getExpireIn()).isEqualTo(2592000);
        assertThat(token.getScope()).isEqualTo("basic");
    }

    @Test
    @DisplayName("34. refreshToken: 底层平台抛出异常时转译为 SocialAuthException 并保留因果链")
    void testRefreshTokenExceptionWrapping() {
        assertThatThrownBy(() -> authTemplate.refreshToken("github_app", "gh_refresh_token"))
                .isInstanceOf(SocialAuthException.class)
                .hasMessageContaining("第三方平台令牌续期异常")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("35. refreshToken: 底层返回错误或未返回有效访问令牌时转译为 SocialAuthException")
    void testRefreshTokenHttpError() {
        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"error\":\"invalid_grant\",\"error_description\":\"refresh token expired\"}"));

        assertThatThrownBy(() -> authTemplate.refreshToken("baidu_app", "baidu_refresh_token"))
                .isInstanceOf(SocialAuthException.class)
                .hasMessageContaining("第三方平台令牌续期异常");
    }

    @Test
    @DisplayName("35b. refreshToken: 底层返回 500 或未返回有效 access_token 时转译为 SocialAuthException")
    void testRefreshTokenEmptyAccessToken() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        assertThatThrownBy(() -> authTemplate.refreshToken("baidu_app", "baidu_refresh_token"))
                .isInstanceOf(SocialAuthException.class)
                .hasMessageContaining("第三方平台令牌续期失败");
    }

    @Test
    @DisplayName("36. revokeAuth: 参数校验前置卫语句拦截")
    void testRevokeAuthValidation() {
        assertThatThrownBy(() -> authTemplate.revokeAuth(null, "some_access_token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.revokeAuth("   ", "some_access_token"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.revokeAuth("baidu_app", (String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.revokeAuth("baidu_app", "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.revokeAuth("baidu_app", (AuthToken) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("37. revokeAuth: 成功吊销授权凭据")
    void testRevokeAuthSuccess() {
        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"result\":1}"));

        authTemplate.revokeAuth("baidu_app", "baidu_access_token_123");
    }

    @Test
    @DisplayName("38. revokeAuth: 底层平台不支持或异常时转译为 SocialAuthException 并保留因果链")
    void testRevokeAuthExceptionWrapping() {
        assertThatThrownBy(() -> authTemplate.revokeAuth("github_app", "gh_access_token"))
                .isInstanceOf(SocialAuthException.class)
                .hasMessageContaining("第三方平台授权吊销异常")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("39. bind: 跨号抢占防卫拦截（已被其他用户绑定时刚性拦截）")
    void testBindConflictDefense() {
        SocialUser pendingUser = SocialUser.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_conflict_001")
                .build();
        String bindTicket = bindTicketStore.createTicket(pendingUser);

        // 模拟第三方账号已经被 user_owner 绑定
        when(userDetailsService.loadUserIdBySocialUser(eq("github_app"), any(SocialUser.class)))
                .thenReturn("user_owner");

        // user_attacker 尝试绑定，应当被拦截阻断
        assertThatThrownBy(() -> authTemplate.bind(bindTicket, "user_attacker"))
                .isInstanceOf(SocialAlreadyBoundException.class)
                .hasMessageContaining("user_owner")
                .hasMessageContaining("github_app");

        // 验证未调用 bindSocialUser
        verify(userDetailsService, never()).bindSocialUser(any(), any(), any());
        // 验证票据已被阅后即焚原子销毁（防重放）
        assertThat(bindTicketStore.contains(bindTicket)).isFalse();
    }

    @Test
    @DisplayName("40. bind: 同用户幂等重复绑定顺利放行")
    void testBindIdempotentSameUser() {
        SocialUser pendingUser = SocialUser.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_same_001")
                .build();
        String bindTicket = bindTicketStore.createTicket(pendingUser);

        // 模拟第三方账号本身就是 user_same 绑定的
        when(userDetailsService.loadUserIdBySocialUser(eq("github_app"), any(SocialUser.class)))
                .thenReturn("user_same");

        SocialAuthResult result = authTemplate.bind(bindTicket, "user_same");
        assertThat(result).isNotNull();
        assertThat(result.isLoggedIn()).isTrue();
        assertThat(result.getUserId()).isEqualTo("user_same");

        verify(userDetailsService).bindSocialUser(eq("user_same"), eq("github_app"), any(SocialUser.class));
    }

    @Test
    @DisplayName("41. bindDirect: 参数校验前置快速失败")
    void testBindDirectValidation() {
        // direct code mode
        assertThatThrownBy(() -> authTemplate.bindDirect(null, "github_app", "code_123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.bindDirect("user_1", null, "code_123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.bindDirect("user_1", "github_app", (String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.bindDirect("user_1", "github_app", "   "))
                .isInstanceOf(IllegalArgumentException.class);

        // callback mode
        assertThatThrownBy(() -> authTemplate.bindDirect(null, "github_app", AuthCallback.builder().code("c").state("s").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.bindDirect("user_1", null, AuthCallback.builder().code("c").state("s").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.bindDirect("user_1", "github_app", (AuthCallback) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("42. bindDirect: direct code 模式直绑成功且不生成新会话")
    void testBindDirectCodeSuccess() {
        String code2SessionJson = "{\"openid\":\"wx_mock_open_777\",\"unionid\":\"wx_mock_union_888\",\"session_key\":\"sess_key_999\"}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(code2SessionJson));

        // 模拟未被任何用户绑定
        when(userDetailsService.loadUserIdBySocialUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn(null);

        // 执行直绑
        SocialUser socialUser = authTemplate.bindDirect("user_login_100", "wechat_mp_app", "mock_wechat_js_code_123");

        assertThat(socialUser).isNotNull();
        assertThat(socialUser.getOpenId()).isEqualTo("wx_mock_open_777");
        assertThat(socialUser.getUnionId()).isEqualTo("wx_mock_union_888");

        // 验证未产生新的登录会话
        assertThat(StpUtil.isLogin()).isFalse();

        // 验证 SPI 被调用
        verify(userDetailsService).bindSocialUser(eq("user_login_100"), eq("wechat_mp_app"), any(SocialUser.class));

        // 验证发布了 SocialBoundEvent 领域事件
        ArgumentCaptor<SocialBoundEvent> eventCaptor = ArgumentCaptor.forClass(SocialBoundEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        SocialBoundEvent boundEvent = eventCaptor.getValue();
        assertThat(boundEvent.getUserId()).isEqualTo("user_login_100");
        assertThat(boundEvent.getAppKey()).isEqualTo("wechat_mp_app");
        assertThat(boundEvent.getSocialUser().getOpenId()).isEqualTo("wx_mock_open_777");
    }

    @Test
    @DisplayName("43. bindDirect: direct code 模式跨号抢占拦截")
    void testBindDirectCodeConflictDefense() {
        String code2SessionJson = "{\"openid\":\"wx_mock_open_777\",\"unionid\":\"wx_mock_union_888\",\"session_key\":\"sess_key_999\"}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(code2SessionJson));

        // 模拟已被 user_other 绑定
        when(userDetailsService.loadUserIdBySocialUser(eq("wechat_mp_app"), any(SocialUser.class)))
                .thenReturn("user_other");

        assertThatThrownBy(() -> authTemplate.bindDirect("user_current", "wechat_mp_app", "mock_wechat_js_code_123"))
                .isInstanceOf(SocialAlreadyBoundException.class)
                .hasMessageContaining("user_other")
                .hasMessageContaining("wechat_mp_app");

        verify(userDetailsService, never()).bindSocialUser(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("44. bindDirect: Web 回调模式直绑成功并核验 State 与恢复租户")
    void testBindDirectCallbackSuccess() {
        StateContext context = StateContext.builder().tenantId("tenant_direct_01").build();
        String state = stateCache.generateState(context);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gho_web_token\",\"token_type\":\"bearer\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":998877,\"login\":\"web_direct_user\",\"name\":\"Web直绑用户\"}"));

        when(userDetailsService.loadUserIdBySocialUser(eq("github_app"), any(SocialUser.class)))
                .thenReturn(null);

        AuthCallback callback = AuthCallback.builder()
                .code("mock_web_code_456")
                .state(state)
                .build();

        SocialUser socialUser = authTemplate.bindDirect("user_web_200", "github_app", callback);

        assertThat(socialUser).isNotNull();
        assertThat(socialUser.getUsername()).isEqualTo("web_direct_user");
        assertThat(stateCache.containsKey(state)).isFalse();

        verify(userDetailsService).bindSocialUser(eq("user_web_200"), eq("github_app"), any(SocialUser.class));
        verify(eventPublisher).publishEvent(any(SocialBoundEvent.class));
    }

    @Test
    @DisplayName("45. bindDirect: Web 回调模式跨号抢占拦截")
    void testBindDirectCallbackConflictDefense() {
        String state = stateCache.generateState(StateContext.builder().build());

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"access_token\":\"gho_web_token\",\"token_type\":\"bearer\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody("{\"id\":998877,\"login\":\"web_direct_user\",\"name\":\"Web直绑用户\"}"));

        when(userDetailsService.loadUserIdBySocialUser(eq("github_app"), any(SocialUser.class)))
                .thenReturn("user_other_bind");

        AuthCallback callback = AuthCallback.builder()
                .code("mock_web_code_456")
                .state(state)
                .build();

        assertThatThrownBy(() -> authTemplate.bindDirect("user_web_200", "github_app", callback))
                .isInstanceOf(SocialAlreadyBoundException.class)
                .hasMessageContaining("user_other_bind")
                .hasMessageContaining("github_app");

        verify(userDetailsService, never()).bindSocialUser(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("46. listBoundAccounts: 参数校验、列表查询与底层返回 null 时的空安全保底")
    void testListBoundAccounts() {
        // 1. 参数校验
        assertThatThrownBy(() -> authTemplate.listBoundAccounts(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authTemplate.listBoundAccounts("   "))
                .isInstanceOf(IllegalArgumentException.class);

        // 2. 底层返回 null 时，防御性返回空列表 Collections.emptyList()
        when(userDetailsService.listBoundAccounts("user_empty")).thenReturn(null);
        List<SocialBoundAccount> emptyResult = authTemplate.listBoundAccounts("user_empty");
        assertThat(emptyResult).isNotNull().isEmpty();

        // 3. 正常查询返回列表
        SocialBoundAccount account = SocialBoundAccount.builder()
                .appKey("github_app")
                .source("GITHUB")
                .openId("gh_123")
                .username("ateng_gh")
                .build();
        when(userDetailsService.listBoundAccounts("user_normal")).thenReturn(List.of(account));

        List<SocialBoundAccount> accounts = authTemplate.listBoundAccounts("user_normal");
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).getAppKey()).isEqualTo("github_app");
        assertThat(accounts.get(0).getUsername()).isEqualTo("ateng_gh");
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
