package io.github.atengk.boot.social.autoconfigure;

import io.github.atengk.boot.social.cache.InMemorySocialAuthStateCache;
import io.github.atengk.boot.social.cache.SocialAuthStateCache;
import io.github.atengk.boot.social.factory.SocialAppFactory;
import io.github.atengk.boot.social.http.SpringRestClientHttpImpl;
import io.github.atengk.boot.social.properties.AtengSocialProperties;
import io.github.atengk.boot.social.session.NoopSocialSessionManager;
import io.github.atengk.boot.social.session.SocialSessionManager;
import io.github.atengk.boot.social.spi.SocialAppConfigProvider;
import io.github.atengk.boot.social.spi.SocialUserDetailsService;
import io.github.atengk.boot.social.template.SocialAuthTemplate;
import io.github.atengk.boot.social.ticket.BindTicketStore;
import io.github.atengk.boot.social.ticket.InMemoryBindTicketStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.cache.AuthStateCache;
import org.dromara.anduin.http.HttpClientAdapter;
import org.dromara.anduin.http.UnifiedHttpClientAccessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 社交登录与 OAuth2 自动装配类
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "io.github.atengk.boot.satoken.autoconfigure.AtengSaTokenAutoConfiguration"
})
@ConditionalOnClass(name = "org.dromara.anduin.AuthRequestBuilder")
@ConditionalOnProperty(prefix = "ateng.boot.social", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengSocialProperties.class)
@Import({RedisSocialCacheConfiguration.class, SaTokenSocialSessionConfiguration.class})
@RequiredArgsConstructor
public class AtengSocialAutoConfiguration {

    private final AtengSocialProperties properties;

    /**
     * 自动装配默认基于 Spring 6 RestClient 的 HTTP 适配器
     *
     * @return HttpClientAdapter 实例
     */
    @Bean
    @ConditionalOnMissingBean(HttpClientAdapter.class)
    public HttpClientAdapter socialHttpClientAdapter() {
        java.net.Proxy proxy = properties.getProxy() != null ? properties.getProxy().toProxy() : null;
        SpringRestClientHttpImpl adapter = new SpringRestClientHttpImpl(
                properties.getConnectTimeout(),
                properties.getReadTimeout(),
                proxy
        );
        UnifiedHttpClientAccessor.setAdapter(adapter);
        log.info("Initialized Spring 6 RestClient adapter for Social OAuth2 communication");
        return adapter;
    }

    /**
     * 自动装配社交应用路由与实例工厂
     *
     * @param configProviderObjectProvider 动态切源 SPI 提供者
     * @param stateCacheObjectProvider    State 缓存提供者
     * @param httpClientAdapter           网络适配器
     * @return SocialAppFactory 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SocialAppFactory socialAppFactory(
            ObjectProvider<SocialAppConfigProvider> configProviderObjectProvider,
            ObjectProvider<AuthStateCache> stateCacheObjectProvider,
            HttpClientAdapter httpClientAdapter) {
        SpringRestClientHttpImpl springClient = httpClientAdapter instanceof SpringRestClientHttpImpl s
                ? s : null;
        SocialAppFactory factory = new SocialAppFactory(properties, configProviderObjectProvider, stateCacheObjectProvider, springClient);
        log.info("Initialized SocialAppFactory with {} static apps configured", properties.getApps().size());
        return factory;
    }

    /**
     * 单机内存兜底防伪 State 状态缓存
     *
     * @return SocialAuthStateCache 实例
     */
    @Bean
    @ConditionalOnMissingBean(SocialAuthStateCache.class)
    public SocialAuthStateCache inMemorySocialAuthStateCache() {
        log.info("Initialized InMemorySocialAuthStateCache for OAuth2 state management");
        return new InMemorySocialAuthStateCache();
    }

    /**
     * 单机内存兜底两阶段绑定票据仓库
     *
     * @return BindTicketStore 实例
     */
    @Bean
    @ConditionalOnMissingBean(BindTicketStore.class)
    public BindTicketStore inMemoryBindTicketStore() {
        log.info("Initialized InMemoryBindTicketStore for two-phase social user binding");
        return new InMemoryBindTicketStore();
    }

    /**
     * 当未引入 Sa-Token 且未自定义实现时的保底会话管理器
     *
     * @return SocialSessionManager 实例
     */
    @Bean
    @ConditionalOnMissingBean(SocialSessionManager.class)
    public SocialSessionManager noopSocialSessionManager() {
        log.info("Initialized NoopSocialSessionManager (Sa-Token not active or no custom session manager provided)");
        return new NoopSocialSessionManager();
    }

    /**
     * 自动装配社交登录核心操作门面
     *
     * @param socialAppFactory                  应用工厂
     * @param stateCache                        防伪状态缓存
     * @param bindTicketStore                   两阶段绑定票据仓库
     * @param userDetailsServiceObjectProvider  用户服务 SPI
     * @param sessionManager                    会话凭据管理器
     * @param eventPublisherObjectProvider      领域事件发布者
     * @return SocialAuthTemplate 门面实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SocialAuthTemplate socialAuthTemplate(
            SocialAppFactory socialAppFactory,
            SocialAuthStateCache stateCache,
            BindTicketStore bindTicketStore,
            ObjectProvider<SocialUserDetailsService> userDetailsServiceObjectProvider,
            SocialSessionManager sessionManager,
            ObjectProvider<ApplicationEventPublisher> eventPublisherObjectProvider) {
        log.info("Initialized SocialAuthTemplate core facade");
        SocialAuthTemplate template = new SocialAuthTemplate(socialAppFactory, stateCache, bindTicketStore, userDetailsServiceObjectProvider, sessionManager);
        if (eventPublisherObjectProvider != null && eventPublisherObjectProvider.getIfAvailable() != null) {
            template.setApplicationEventPublisher(eventPublisherObjectProvider.getIfAvailable());
        }
        return template;
    }

    @PostConstruct
    public void init() {
        log.info("ateng-boot-social autoconfiguration activated successfully");
    }
}
