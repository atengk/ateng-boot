package io.github.atengk.boot.social.factory;

import io.github.atengk.boot.social.config.SocialAppConfig;
import io.github.atengk.boot.social.context.CurrentSocialAppContextHolder;
import io.github.atengk.boot.social.exception.SocialAppNotFoundException;
import io.github.atengk.boot.social.http.SocialProxyConfig;
import io.github.atengk.boot.social.http.SpringRestClientHttpImpl;
import io.github.atengk.boot.social.properties.AtengSocialProperties;
import io.github.atengk.boot.social.properties.SocialAppItemProperties;
import io.github.atengk.boot.social.spi.SocialAppConfigProvider;
import org.dromara.anduin.cache.AuthStateCache;
import org.dromara.anduin.config.AuthConfig;
import org.dromara.anduin.config.AuthSource;
import org.dromara.anduin.model.AuthCallback;
import org.dromara.anduin.model.AuthToken;
import org.dromara.anduin.model.AuthUser;
import org.dromara.anduin.request.AuthAfDianRequest;
import org.dromara.anduin.request.AuthDefaultRequest;
import org.dromara.anduin.request.AuthGiteeRequest;
import org.dromara.anduin.request.AuthGithubRequest;
import org.dromara.anduin.request.AuthQQMiniProgramRequest;
import org.dromara.anduin.request.AuthRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;

import java.net.Proxy;
import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SocialAppFactory 应用路由与实例工厂对抗性单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class SocialAppFactoryTest {

    private AtengSocialProperties properties;
    private ObjectProvider<SocialAppConfigProvider> configProviderProvider;
    private ObjectProvider<AuthStateCache> stateCacheProvider;
    private SpringRestClientHttpImpl httpClientAdapter;
    private SocialAppFactory factory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new AtengSocialProperties();
        configProviderProvider = mock(ObjectProvider.class);
        stateCacheProvider = mock(ObjectProvider.class);
        httpClientAdapter = new SpringRestClientHttpImpl();

        // 预设本地静态应用配置
        SocialAppItemProperties githubProps = new SocialAppItemProperties();
        githubProps.setSource("GITHUB");
        githubProps.setClientId("mock-github-client-id");
        githubProps.setClientSecret("mock-github-secret");
        githubProps.setRedirectUri("https://example.com/oauth/github/callback");
        SocialProxyConfig proxyConfig = new SocialProxyConfig();
        proxyConfig.setType(Proxy.Type.HTTP);
        proxyConfig.setHost("127.0.0.1");
        proxyConfig.setPort(7890);
        githubProps.setProxy(proxyConfig);
        properties.getApps().put("github-main", githubProps);

        SocialAppItemProperties giteeProps = new SocialAppItemProperties();
        giteeProps.setSource("GITEE");
        giteeProps.setClientId("mock-gitee-client-id");
        giteeProps.setClientSecret("mock-gitee-secret");
        giteeProps.setRedirectUri("https://example.com/oauth/gitee/callback");
        properties.getApps().put("gitee-main", giteeProps);

        factory = new SocialAppFactory(properties, configProviderProvider, stateCacheProvider, httpClientAdapter);
    }

    @AfterEach
    void tearDown() {
        CurrentSocialAppContextHolder.clear();
    }

    @Test
    @DisplayName("验证静态 YAML 配置匹配并构建对应 AuthRequest 实例")
    void testGetAppConfigAndCreateAuthRequest_FromStaticProperties() {
        SocialAppConfig config = factory.getAppConfig("github-main");
        assertThat(config).isNotNull();
        assertThat(config.getAppKey()).isEqualTo("github-main");
        assertThat(config.getSource()).isEqualTo("GITHUB");
        assertThat(config.getClientId()).isEqualTo("mock-github-client-id");
        assertThat(config.getProxy()).isNotNull();

        AuthRequest authRequest = factory.createAuthRequest("github-main");
        assertThat(authRequest).isNotNull();
        assertThat(authRequest).isInstanceOf(AppKeyAwareAuthRequest.class);
        AppKeyAwareAuthRequest appKeyAwareRequest = (AppKeyAwareAuthRequest) authRequest;
        assertThat(appKeyAwareRequest.getAppKey()).isEqualTo("github-main");
        assertThat(appKeyAwareRequest.getDelegate()).isInstanceOf(AuthGithubRequest.class);

        // 验证执行时自动绑定 CurrentSocialAppContextHolder 并在完成后清理
        String authorizeUrl = authRequest.authorize("state_test");
        assertThat(authorizeUrl).contains("state=state_test");
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isNull();

        AuthRequest giteeRequest = factory.createAuthRequest("gitee-main");
        assertThat(giteeRequest).isNotNull();
        assertThat(giteeRequest).isInstanceOf(AppKeyAwareAuthRequest.class);
        assertThat(((AppKeyAwareAuthRequest) giteeRequest).getDelegate()).isInstanceOf(AuthGiteeRequest.class);
    }

    @Test
    @DisplayName("验证动态切源 SPI (SocialAppConfigProvider) 优先接管并切源")
    void testDynamicAppConfigProvider_Precedence() {
        SocialAppConfigProvider mockProvider = mock(SocialAppConfigProvider.class);
        when(configProviderProvider.getIfAvailable()).thenReturn(mockProvider);

        SocialAppConfig dynamicTenantConfig = SocialAppConfig.builder()
                .appKey("tenant-gitee")
                .source("GITEE")
                .clientId("dynamic-tenant-client")
                .clientSecret("dynamic-tenant-secret")
                .redirectUri("https://tenant.example.com/callback")
                .build();

        when(mockProvider.getAppConfig("tenant-gitee")).thenReturn(dynamicTenantConfig);

        SocialAppConfig resolvedConfig = factory.getAppConfig("tenant-gitee");
        assertThat(resolvedConfig).isNotNull();
        assertThat(resolvedConfig.getClientId()).isEqualTo("dynamic-tenant-client");

        AuthRequest request = factory.createAuthRequest("tenant-gitee");
        assertThat(request).isNotNull();
        assertThat(request).isInstanceOf(AppKeyAwareAuthRequest.class);
        assertThat(((AppKeyAwareAuthRequest) request).getDelegate()).isInstanceOf(AuthGiteeRequest.class);
    }

    @Test
    @DisplayName("验证异常边界：不存在的 appKey 或空入参强抛 SocialAppNotFoundException")
    void testNotFoundAndBlankValidation() {
        assertThatThrownBy(() -> factory.getAppConfig("non-exist-app"))
                .isInstanceOf(SocialAppNotFoundException.class)
                .hasMessageContaining("non-exist-app");

        assertThatThrownBy(() -> factory.getAppConfig(""))
                .isInstanceOf(SocialAppNotFoundException.class);

        assertThatThrownBy(() -> factory.getAppConfig(null))
                .isInstanceOf(SocialAppNotFoundException.class);
    }

    @Test
    @DisplayName("验证已注册应用清单与上下文持有者清理")
    void testRegisteredAppKeysAndContextHolder() {
        assertThat(factory.getRegisteredAppKeys()).containsExactlyInAnyOrder("github-main", "gitee-main");

        CurrentSocialAppContextHolder.setAppKey("github-main");
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isEqualTo("github-main");

        CurrentSocialAppContextHolder.clear();
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isNull();
    }

    @Test
    @DisplayName("验证上游 AFDIAN 与 QQ_MINI_PROGRAM 映射缺陷自动修复")
    void testUpstreamAfdianAndQqMiniProgramSelfHealing() {
        SocialAppItemProperties afdianProps = new SocialAppItemProperties();
        afdianProps.setSource("AFDIAN");
        afdianProps.setClientId("afdian-client-id");
        afdianProps.setClientSecret("afdian-client-secret");
        afdianProps.setRedirectUri("https://example.com/oauth/afdian/callback");
        properties.getApps().put("afdian-app", afdianProps);

        SocialAppItemProperties qqMiniProps = new SocialAppItemProperties();
        qqMiniProps.setSource("QQ_MINI_PROGRAM");
        qqMiniProps.setClientId("qq-mini-appid");
        qqMiniProps.setClientSecret("qq-mini-secret");
        qqMiniProps.setIgnoreCheckState(true);
        qqMiniProps.setIgnoreCheckRedirectUri(true);
        properties.getApps().put("qq-mini-app", qqMiniProps);

        AuthRequest afdianRequest = factory.createAuthRequest("afdian-app");
        assertThat(afdianRequest).isInstanceOf(AppKeyAwareAuthRequest.class);
        assertThat(((AppKeyAwareAuthRequest) afdianRequest).getDelegate()).isInstanceOf(AuthAfDianRequest.class);

        AuthRequest qqMiniRequest = factory.createAuthRequest("qq-mini-app");
        assertThat(qqMiniRequest).isInstanceOf(AppKeyAwareAuthRequest.class);
        assertThat(((AppKeyAwareAuthRequest) qqMiniRequest).getDelegate()).isInstanceOf(AuthQQMiniProgramRequest.class);
    }

    @Test
    @DisplayName("验证自定义 AuthSource 容器自动发现、编程式注册注销及同名优先级覆写")
    void testCustomExtendSourceRegistrationAndPrecedence() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("customKeycloakSource", CustomEnterpriseSource.KEYCLOAK);

        SocialAppFactory customFactory = new SocialAppFactory(
                properties,
                configProviderProvider,
                stateCacheProvider,
                httpClientAdapter,
                beanFactory.getBeanProvider(AuthSource.class)
        );

        assertThat(customFactory.getExtendSourceNames()).contains("KEYCLOAK");

        SocialAppItemProperties keycloakProps = new SocialAppItemProperties();
        keycloakProps.setSource("keycloak");
        keycloakProps.setClientId("kc-client");
        keycloakProps.setClientSecret("kc-secret");
        keycloakProps.setRedirectUri("https://example.com/kc/callback");
        properties.getApps().put("keycloak-app", keycloakProps);

        AuthRequest kcRequest = customFactory.createAuthRequest("keycloak-app");
        assertThat(((AppKeyAwareAuthRequest) kcRequest).getDelegate()).isInstanceOf(CustomKeycloakRequest.class);
        assertThat(kcRequest.authorize("st_kc")).startsWith("https://sso.enterprise.internal/auth");

        // 注册与内置 GITHUB 同名的自定义 AuthSource，验证优先命中自定义源而非被内置枚举遮蔽
        customFactory.registerExtendSource(CustomEnterpriseSource.GITHUB);
        assertThat(customFactory.getExtendSourceNames()).contains("KEYCLOAK", "GITHUB");

        AuthRequest overriddenGithub = customFactory.createAuthRequest("github-main");
        assertThat(((AppKeyAwareAuthRequest) overriddenGithub).getDelegate()).isInstanceOf(CustomKeycloakRequest.class);

        // 对称注销同名扩展源后，恢复命中内置 AuthGithubRequest
        boolean removed = customFactory.unregisterExtendSource("github");
        assertThat(removed).isTrue();
        assertThat(customFactory.getExtendSourceNames()).doesNotContain("GITHUB");

        AuthRequest restoredGithub = customFactory.createAuthRequest("github-main");
        assertThat(((AppKeyAwareAuthRequest) restoredGithub).getDelegate()).isInstanceOf(AuthGithubRequest.class);
    }

    @Test
    @DisplayName("验证多租户动态切源下 RestClient 配置指纹热更新与回退清理")
    void testDynamicAppClientFingerprintHotUpdateAndEviction() {
        SocialAppConfigProvider mockProvider = mock(SocialAppConfigProvider.class);
        when(configProviderProvider.getIfAvailable()).thenReturn(mockProvider);

        RestClient defaultClient = httpClientAdapter.obtainClient();

        // 1. 首次动态配置携带 3s 超时与 7890 代理
        SocialAppConfig configV1 = SocialAppConfig.builder()
                .appKey("dynamic-app")
                .source("GITEE")
                .clientId("c1")
                .clientSecret("s1")
                .redirectUri("https://example.com/cb")
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(3))
                .proxy(SocialProxyConfig.builder().type(Proxy.Type.HTTP).host("127.0.0.1").port(7890).build())
                .build();
        when(mockProvider.getAppConfig("dynamic-app")).thenReturn(configV1);

        factory.createAuthRequest("dynamic-app");
        RestClient clientV1 = CurrentSocialAppContextHolder.runWithAppKey("dynamic-app", () -> httpClientAdapter.obtainClient());
        assertThat(clientV1).isNotSameAs(defaultClient);

        // 2. 配置未变更时，复用同一专有 RestClient 实例
        factory.createAuthRequest("dynamic-app");
        RestClient clientV1Repeat = CurrentSocialAppContextHolder.runWithAppKey("dynamic-app", () -> httpClientAdapter.obtainClient());
        assertThat(clientV1Repeat).isSameAs(clientV1);

        // 3. 租户后台热修改超时为 8s、代理端口改为 1080 -> 指纹变化，自动热替换 RestClient
        SocialAppConfig configV2 = SocialAppConfig.builder()
                .appKey("dynamic-app")
                .source("GITEE")
                .clientId("c1")
                .clientSecret("s1")
                .redirectUri("https://example.com/cb")
                .connectTimeout(Duration.ofSeconds(8))
                .readTimeout(Duration.ofSeconds(8))
                .proxy(SocialProxyConfig.builder().type(Proxy.Type.SOCKS).host("127.0.0.1").port(1080).build())
                .build();
        when(mockProvider.getAppConfig("dynamic-app")).thenReturn(configV2);

        factory.createAuthRequest("dynamic-app");
        RestClient clientV2 = CurrentSocialAppContextHolder.runWithAppKey("dynamic-app", () -> httpClientAdapter.obtainClient());
        assertThat(clientV2).isNotSameAs(defaultClient).isNotSameAs(clientV1);

        // 4. 租户移除专有代理与超时配置 -> 自动卸载专有客户端并回退至默认 RestClient
        SocialAppConfig configV3NoCustomHttp = SocialAppConfig.builder()
                .appKey("dynamic-app")
                .source("GITEE")
                .clientId("c1")
                .clientSecret("s1")
                .redirectUri("https://example.com/cb")
                .build();
        when(mockProvider.getAppConfig("dynamic-app")).thenReturn(configV3NoCustomHttp);

        factory.createAuthRequest("dynamic-app");
        RestClient clientV3 = CurrentSocialAppContextHolder.runWithAppKey("dynamic-app", () -> httpClientAdapter.obtainClient());
        assertThat(clientV3).isSameAs(defaultClient);

        // 5. 验证工厂显式对称注销与清空 API (unregisterAppRestClient / clearAppRestClients)
        when(mockProvider.getAppConfig("dynamic-app")).thenReturn(configV1);
        factory.createAuthRequest("dynamic-app");
        assertThat(CurrentSocialAppContextHolder.runWithAppKey("dynamic-app", () -> httpClientAdapter.obtainClient()))
                .isNotSameAs(defaultClient);

        factory.unregisterAppRestClient("dynamic-app");
        assertThat(CurrentSocialAppContextHolder.runWithAppKey("dynamic-app", () -> httpClientAdapter.obtainClient()))
                .isSameAs(defaultClient);

        factory.createAuthRequest("dynamic-app");
        assertThat(CurrentSocialAppContextHolder.runWithAppKey("dynamic-app", () -> httpClientAdapter.obtainClient()))
                .isNotSameAs(defaultClient);

        factory.clearAppRestClients();
        assertThat(CurrentSocialAppContextHolder.runWithAppKey("dynamic-app", () -> httpClientAdapter.obtainClient()))
                .isSameAs(defaultClient);
    }

    @Test
    @DisplayName("验证企业级平台专有配置 (PKCE / 微软 Entra / 企微V2 / 钉钉V2 / Apple / Okta) 100% 映射至 AuthConfig")
    void testEnterprisePlatformSpecificPropertiesMapping() {
        SocialAppItemProperties enterpriseProps = new SocialAppItemProperties();
        enterpriseProps.setSource("MICROSOFT");
        enterpriseProps.setClientId("ms-client-id");
        enterpriseProps.setClientSecret("ms-client-secret");
        enterpriseProps.setRedirectUri("https://ateng.example.com/oauth/ms/callback");
        enterpriseProps.setPkce(true);
        enterpriseProps.setMicrosoftTenantId("ms-directory-tenant-guid");
        enterpriseProps.setTenantId("legacy-fallback-tenant");
        enterpriseProps.setKid("apple-kid-123");
        enterpriseProps.setTeamId("apple-team-456");
        enterpriseProps.setLoginType("ServiceApp");
        enterpriseProps.setLang("en");
        enterpriseProps.setUsertype("admin");
        enterpriseProps.setDingTalkOrgType("management");
        enterpriseProps.setDingTalkCorpId("ding-corp-1");
        enterpriseProps.setDingTalkExclusiveLogin(true);
        enterpriseProps.setDingTalkExclusiveCorpId("ding-excl-corp-2");
        enterpriseProps.setDomainPrefix("ateng-okta");
        enterpriseProps.setAuthServerId("custom-auth-srv");
        enterpriseProps.setDeviceId("dev-001");
        enterpriseProps.setClientOsType(1);
        enterpriseProps.setPackId("io.github.atengk.app");
        enterpriseProps.setStackOverflowKey("so-key-999");
        enterpriseProps.setRejectSnapshotUser(true);

        properties.getApps().put("enterprise-ms", enterpriseProps);

        SocialAppConfig appConfig = factory.getAppConfig("enterprise-ms");
        assertThat(appConfig.isPkce()).isTrue();
        assertThat(appConfig.getMicrosoftTenantId()).isEqualTo("ms-directory-tenant-guid");
        assertThat(appConfig.getRejectSnapshotUser()).isTrue();

        AuthConfig authConfig = appConfig.toAuthConfig();
        assertThat(authConfig.isPkce()).isTrue();
        // 优先取 microsoftTenantId
        assertThat(authConfig.getTenantId()).isEqualTo("ms-directory-tenant-guid");
        assertThat(authConfig.getKid()).isEqualTo("apple-kid-123");
        assertThat(authConfig.getTeamId()).isEqualTo("apple-team-456");
        assertThat(authConfig.getLoginType()).isEqualTo("ServiceApp");
        assertThat(authConfig.getLang()).isEqualTo("en");
        assertThat(authConfig.getUsertype()).isEqualTo("admin");
        assertThat(authConfig.getDingTalkOrgType()).isEqualTo("management");
        assertThat(authConfig.getDingTalkCorpId()).isEqualTo("ding-corp-1");
        assertThat(authConfig.isDingTalkExclusiveLogin()).isTrue();
        assertThat(authConfig.getDingTalkExclusiveCorpId()).isEqualTo("ding-excl-corp-2");
        assertThat(authConfig.getDomainPrefix()).isEqualTo("ateng-okta");
        assertThat(authConfig.getAuthServerId()).isEqualTo("custom-auth-srv");
        assertThat(authConfig.getDeviceId()).isEqualTo("dev-001");
        assertThat(authConfig.getClientOsType()).isEqualTo(1);
        assertThat(authConfig.getPackId()).isEqualTo("io.github.atengk.app");
        assertThat(authConfig.getStackOverflowKey()).isEqualTo("so-key-999");

        // 当 microsoftTenantId 未设置时，平滑回退至 tenantId
        SocialAppConfig fallbackMsConfig = SocialAppConfig.builder()
                .clientId("c")
                .clientSecret("s")
                .tenantId("fallback-ms-tenant")
                .build();
        assertThat(fallbackMsConfig.toAuthConfig().getTenantId()).isEqualTo("fallback-ms-tenant");
    }

    public enum CustomEnterpriseSource implements AuthSource {
        KEYCLOAK,
        GITHUB;

        @Override
        public String authorize() {
            return "https://sso.enterprise.internal/auth";
        }

        @Override
        public String accessToken() {
            return "https://sso.enterprise.internal/token";
        }

        @Override
        public String userInfo() {
            return "https://sso.enterprise.internal/userinfo";
        }

        @Override
        public Class<? extends AuthDefaultRequest> getTargetClass() {
            return CustomKeycloakRequest.class;
        }
    }

    public static class CustomKeycloakRequest extends AuthDefaultRequest {
        public CustomKeycloakRequest(AuthConfig config) {
            super(config, CustomEnterpriseSource.KEYCLOAK);
        }

        public CustomKeycloakRequest(AuthConfig config, AuthStateCache authStateCache) {
            super(config, CustomEnterpriseSource.KEYCLOAK, authStateCache);
        }

        @Override
        public AuthToken getAccessToken(AuthCallback authCallback) {
            return AuthToken.builder().accessToken("kc-token").openId("kc-open-1").build();
        }

        @Override
        public AuthUser getUserInfo(AuthToken authToken) {
            return AuthUser.builder().uuid("kc-open-1").username("kc_user").token(authToken).source(source.getName()).build();
        }
    }
}
