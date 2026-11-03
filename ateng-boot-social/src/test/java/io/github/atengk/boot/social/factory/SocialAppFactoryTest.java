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
import org.dromara.anduin.request.AuthGiteeRequest;
import org.dromara.anduin.request.AuthGithubRequest;
import org.dromara.anduin.request.AuthRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
}
