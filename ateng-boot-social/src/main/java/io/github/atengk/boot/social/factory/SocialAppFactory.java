package io.github.atengk.boot.social.factory;

import io.github.atengk.boot.social.config.SocialAppConfig;
import io.github.atengk.boot.social.exception.SocialAppNotFoundException;
import io.github.atengk.boot.social.exception.SocialException;
import io.github.atengk.boot.social.http.SocialProxyConfig;
import io.github.atengk.boot.social.http.SpringRestClientHttpImpl;
import io.github.atengk.boot.social.properties.AtengSocialProperties;
import io.github.atengk.boot.social.properties.SocialAppItemProperties;
import io.github.atengk.boot.social.spi.SocialAppConfigProvider;
import lombok.extern.slf4j.Slf4j;
import org.dromara.anduin.AuthRequestBuilder;
import org.dromara.anduin.cache.AuthDefaultStateCache;
import org.dromara.anduin.cache.AuthStateCache;
import org.dromara.anduin.config.AuthConfig;
import org.dromara.anduin.request.AuthRequest;
import org.springframework.beans.factory.ObjectProvider;

import java.net.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 社交应用路由与 OAuth2 请求实例工厂
 * <p>
 * 统一管理第三方平台应用配置，支持从 YAML 静态属性自动解析，亦支持通过 {@link SocialAppConfigProvider} 动态多租户切源；
 * 同时负责应用级出站代理与专有网络客户端实例的动态路由与装配。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
public class SocialAppFactory {

    private final AtengSocialProperties properties;
    private final ObjectProvider<SocialAppConfigProvider> configProviderObjectProvider;
    private final ObjectProvider<AuthStateCache> stateCacheObjectProvider;
    private final SpringRestClientHttpImpl httpClientAdapter;
    private final Set<String> registeredProxyAppKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public SocialAppFactory(AtengSocialProperties properties,
                            ObjectProvider<SocialAppConfigProvider> configProviderObjectProvider,
                            ObjectProvider<AuthStateCache> stateCacheObjectProvider,
                            SpringRestClientHttpImpl httpClientAdapter) {
        this.properties = properties != null ? properties : new AtengSocialProperties();
        this.configProviderObjectProvider = configProviderObjectProvider;
        this.stateCacheObjectProvider = stateCacheObjectProvider;
        this.httpClientAdapter = httpClientAdapter;
    }

    /**
     * 获取指定社交应用的标准化配置
     * <p>
     * 1. 优先尝试通过 {@link SocialAppConfigProvider} SPI 获取当前多租户环境下的动态配置；
     * 2. 若未获取到，则回退查找本地 YAML 静态配置字典；
     * 3. 均未检索到时，抛出业务受管异常 {@link SocialAppNotFoundException}。
     * </p>
     *
     * @param appKey 社交应用唯一标识
     * @return 社交应用标准配置
     * @throws SocialAppNotFoundException 当配置不存在时抛出
     */
    public SocialAppConfig getAppConfig(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            throw new SocialAppNotFoundException("应用标识 [appKey] 不能为空");
        }

        // 1. 优先从动态 SPI 提供者获取（多租户/DB切源）
        if (configProviderObjectProvider != null) {
            SocialAppConfigProvider provider = configProviderObjectProvider.getIfAvailable();
            if (provider != null) {
                SocialAppConfig dynamicConfig = provider.getAppConfig(appKey);
                if (dynamicConfig != null) {
                    return dynamicConfig;
                }
            }
        }

        // 2. 从本地静态 YAML 配置中查找
        SocialAppItemProperties itemProps = properties.getApps().get(appKey);
        if (itemProps != null) {
            return convertToAppConfig(appKey, itemProps);
        }

        // 3. 均未匹配，抛出业务受管异常
        throw new SocialAppNotFoundException(appKey);
    }

    /**
     * 根据应用唯一标识创建或获取对应的 OAuth2 认证请求执行器 {@link AuthRequest}
     *
     * @param appKey 社交应用唯一标识
     * @return 对应的 AuthRequest 请求实例
     */
    public AuthRequest createAuthRequest(String appKey) {
        SocialAppConfig appConfig = getAppConfig(appKey);
        ensureAppProxyRegistered(appKey, appConfig);

        AuthStateCache stateCache = null;
        if (stateCacheObjectProvider != null) {
            stateCache = stateCacheObjectProvider.getIfAvailable();
        }
        if (stateCache == null) {
            stateCache = AuthDefaultStateCache.INSTANCE;
        }

        AuthConfig anduinAuthConfig = appConfig.toAuthConfig();

        try {
            AuthRequest rawAuthRequest = AuthRequestBuilder.builder()
                    .source(appConfig.getSource())
                    .authConfig(anduinAuthConfig)
                    .authStateCache(stateCache)
                    .build();
            return new AppKeyAwareAuthRequest(appKey, rawAuthRequest);
        } catch (Exception ex) {
            log.error("Failed to build AuthRequest for appKey: {}, source: {}", appKey, appConfig.getSource(), ex);
            throw new SocialException("构建第三方 OAuth2 请求实例失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 获取当前所有已注册的静态应用标识列表
     *
     * @return 应用标识集合
     */
    public Collection<String> getRegisteredAppKeys() {
        if (properties.getApps() == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableSet(properties.getApps().keySet());
    }

    /**
     * 若应用配置了专有网络代理或超时且尚未注册至适配器，则执行动态注册
     */
    private void ensureAppProxyRegistered(String appKey, SocialAppConfig appConfig) {
        if (httpClientAdapter == null || registeredProxyAppKeys.contains(appKey)) {
            return;
        }

        SocialProxyConfig proxyConfig = appConfig.getProxy();
        Duration connectTimeout = appConfig.getConnectTimeout();
        Duration readTimeout = appConfig.getReadTimeout();

        if (proxyConfig != null || connectTimeout != null || readTimeout != null) {
            Proxy proxy = proxyConfig != null ? proxyConfig.toProxy() : null;
            httpClientAdapter.registerAppRestClient(appKey, connectTimeout, readTimeout, proxy);
            registeredProxyAppKeys.add(appKey);
            log.info("Registered dedicated RestClient for social appKey: [{}], proxy: [{}], timeout: [{}, {}]",
                    appKey, proxy != null ? proxy.type() : "DIRECT", connectTimeout, readTimeout);
        }
    }

    /**
     * 将 YAML 属性项映射转换为领域配置模型
     */
    private SocialAppConfig convertToAppConfig(String appKey, SocialAppItemProperties props) {
        return SocialAppConfig.builder()
                .appKey(appKey)
                .source(props.getSource())
                .clientId(props.getClientId())
                .clientSecret(props.getClientSecret())
                .redirectUri(props.getRedirectUri())
                .serverUrl(props.getServerUrl())
                .scopes(props.getScopes() != null ? props.getScopes() : Collections.emptyList())
                .ignoreCheckState(props.isIgnoreCheckState())
                .ignoreCheckRedirectUri(props.isIgnoreCheckRedirectUri())
                .agentId(props.getAgentId())
                .alipayPublicKey(props.getAlipayPublicKey())
                .unionId(props.isUnionId())
                .proxy(props.getProxy())
                .connectTimeout(props.getConnectTimeout())
                .readTimeout(props.getReadTimeout())
                .build();
    }
}
