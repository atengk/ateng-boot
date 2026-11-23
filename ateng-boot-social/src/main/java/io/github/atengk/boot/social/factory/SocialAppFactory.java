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
import org.dromara.anduin.config.AuthSource;
import org.dromara.anduin.enums.AuthResponseStatus;
import org.dromara.anduin.exception.AuthException;
import org.dromara.anduin.request.AuthAfDianRequest;
import org.dromara.anduin.request.AuthDefaultRequest;
import org.dromara.anduin.request.AuthQQMiniProgramRequest;
import org.dromara.anduin.request.AuthRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
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

    private static final Map<String, Class<? extends AuthDefaultRequest>> BUILTIN_PATCH_REQUEST_CLASSES = Map.of(
            "AFDIAN", AuthAfDianRequest.class,
            "QQ_MINI_PROGRAM", AuthQQMiniProgramRequest.class
    );

    private final AtengSocialProperties properties;
    private final ObjectProvider<SocialAppConfigProvider> configProviderObjectProvider;
    private final ObjectProvider<AuthStateCache> stateCacheObjectProvider;
    private final SpringRestClientHttpImpl httpClientAdapter;
    private final Map<String, AppClientFingerprint> appClientFingerprints = new ConcurrentHashMap<>();
    private final Map<String, AuthSource> extendSources = new ConcurrentHashMap<>();

    public SocialAppFactory(AtengSocialProperties properties,
                            ObjectProvider<SocialAppConfigProvider> configProviderObjectProvider,
                            ObjectProvider<AuthStateCache> stateCacheObjectProvider,
                            SpringRestClientHttpImpl httpClientAdapter) {
        this(properties, configProviderObjectProvider, stateCacheObjectProvider, httpClientAdapter, null);
    }

    public SocialAppFactory(AtengSocialProperties properties,
                            ObjectProvider<SocialAppConfigProvider> configProviderObjectProvider,
                            ObjectProvider<AuthStateCache> stateCacheObjectProvider,
                            SpringRestClientHttpImpl httpClientAdapter,
                            ObjectProvider<AuthSource> extendSourceObjectProvider) {
        this.properties = properties != null ? properties : new AtengSocialProperties();
        this.configProviderObjectProvider = configProviderObjectProvider;
        this.stateCacheObjectProvider = stateCacheObjectProvider;
        this.httpClientAdapter = httpClientAdapter;
        if (extendSourceObjectProvider != null) {
            extendSourceObjectProvider.orderedStream().forEach(this::registerExtendSource);
        }
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
            AuthRequest rawAuthRequest = resolveAndBuildAuthRequest(appConfig.getSource(), anduinAuthConfig, stateCache);
            return new AppKeyAwareAuthRequest(appKey, rawAuthRequest);
        } catch (Exception ex) {
            log.error("Failed to build AuthRequest for appKey: {}, source: {}", appKey, appConfig.getSource(), ex);
            throw new SocialException("构建第三方 OAuth2 请求实例失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 按 [自定义扩展 AuthSource > 内置缺陷修正源 (AFDIAN / QQ_MINI_PROGRAM) > AuthDefaultSource] 优先级构建请求实例
     */
    private AuthRequest resolveAndBuildAuthRequest(String rawSource, AuthConfig authConfig, AuthStateCache stateCache) {
        if (rawSource == null || rawSource.isBlank()) {
            throw new AuthException(AuthResponseStatus.NOT_IMPLEMENTED.getCode(), "Missing source or authConfig");
        }
        String normalizedSource = rawSource.trim().toUpperCase(Locale.ROOT);

        // 1. 优先匹配自定义注册的 AuthSource（避免被上游 AuthRequestBuilder.concat 中的内置枚举遮蔽）
        AuthSource customSource = extendSources.get(normalizedSource);
        if (customSource != null) {
            return instantiateTargetRequest(customSource.getName(), customSource.getTargetClass(), authConfig, stateCache);
        }

        // 2. 匹配上游 dromara-anduin 内置枚举映射缺陷修正源（AFDIAN 与 QQ_MINI_PROGRAM）
        Class<? extends AuthDefaultRequest> patchedClass = BUILTIN_PATCH_REQUEST_CLASSES.get(normalizedSource);
        if (patchedClass != null) {
            return instantiateTargetRequest(normalizedSource, patchedClass, authConfig, stateCache);
        }

        // 3. 回退至 Anduin 标准构建器
        return AuthRequestBuilder.builder()
                .source(rawSource.trim())
                .authConfig(authConfig)
                .authStateCache(stateCache)
                .extendSource(extendSources.values().toArray(new AuthSource[0]))
                .build();
    }

    private AuthRequest instantiateTargetRequest(String sourceName,
                                                 Class<? extends AuthDefaultRequest> targetClass,
                                                 AuthConfig authConfig,
                                                 AuthStateCache stateCache) {
        if (targetClass == null) {
            throw new AuthException(AuthResponseStatus.NOT_IMPLEMENTED.getCode(),
                    "No request class for source: " + sourceName);
        }
        try {
            if (stateCache != null) {
                try {
                    Constructor<? extends AuthDefaultRequest> ctor =
                            targetClass.getDeclaredConstructor(AuthConfig.class, AuthStateCache.class);
                    ReflectionUtils.makeAccessible(ctor);
                    return ctor.newInstance(authConfig, stateCache);
                } catch (NoSuchMethodException ignored) {
                    // 回退至单参构造器
                }
            }
            Constructor<? extends AuthDefaultRequest> singleCtor =
                    targetClass.getDeclaredConstructor(AuthConfig.class);
            ReflectionUtils.makeAccessible(singleCtor);
            return singleCtor.newInstance(authConfig);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof AuthException authEx) {
                throw authEx;
            }
            throw new AuthException("Unable to construct request for source " + sourceName
                    + " using " + targetClass.getName(), cause);
        } catch (ReflectiveOperationException | SecurityException | LinkageError ex) {
            throw new AuthException("Unable to construct request for source " + sourceName
                    + " using " + targetClass.getName(), ex);
        }
    }

    /**
     * 动态注册自定义第三方 OAuth2 / OIDC 平台源
     *
     * @param sources 自定义平台源数组
     */
    public void registerExtendSource(AuthSource... sources) {
        if (sources == null || sources.length == 0) {
            return;
        }
        for (AuthSource source : sources) {
            if (source != null && source.getName() != null && !source.getName().isBlank()) {
                String key = source.getName().trim().toUpperCase(Locale.ROOT);
                extendSources.put(key, source);
                log.info("Registered custom social AuthSource: [{}]", key);
            }
        }
    }

    /**
     * 对称注销自定义第三方 OAuth2 / OIDC 平台源
     *
     * @param sourceName 平台源名称（忽略大小写）
     * @return true 表示存在并已成功移除；false 表示未注册
     */
    public boolean unregisterExtendSource(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return false;
        }
        String key = sourceName.trim().toUpperCase(Locale.ROOT);
        AuthSource removed = extendSources.remove(key);
        if (removed != null) {
            log.info("Unregistered custom social AuthSource: [{}]", key);
            return true;
        }
        return false;
    }

    /**
     * 获取当前已注册的全部自定义扩展平台名称集合
     *
     * @return 不可变平台名称集合
     */
    public Collection<String> getExtendSourceNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(extendSources.keySet()));
    }

    /**
     * 解析实际生效的平台源名称（优先返回自定义 AuthSource 的原始 getName，用于精确对齐上游缓存键）
     *
     * @param rawSource 配置中的平台标识
     * @return 实际平台源名称；入参为空时返回 null
     */
    public String resolveSourceName(String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return null;
        }
        String trimmed = rawSource.trim();
        String normalizedSource = trimmed.toUpperCase(Locale.ROOT);
        AuthSource customSource = extendSources.get(normalizedSource);
        if (customSource != null && customSource.getName() != null && !customSource.getName().isBlank()) {
            return customSource.getName();
        }
        return normalizedSource;
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
     * 对称移除指定应用的专属 RestClient 缓存
     *
     * @param appKey 社交应用唯一标识
     */
    public void unregisterAppRestClient(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return;
        }
        appClientFingerprints.compute(appKey, (key, previous) -> {
            if (httpClientAdapter != null) {
                httpClientAdapter.unregisterAppRestClient(key);
            }
            return null;
        });
    }

    /**
     * 清空全部应用级专属 RestClient 缓存
     */
    public void clearAppRestClients() {
        for (String appKey : appClientFingerprints.keySet()) {
            unregisterAppRestClient(appKey);
        }
        if (httpClientAdapter != null) {
            httpClientAdapter.clearAppRestClients();
        }
    }

    /**
     * 若应用配置了专有网络代理或超时，按配置指纹原子注册或热更新至适配器；若已移除专有配置则对称卸载
     */
    private void ensureAppProxyRegistered(String appKey, SocialAppConfig appConfig) {
        if (httpClientAdapter == null || appKey == null || appKey.isBlank()) {
            return;
        }

        SocialProxyConfig proxyConfig = appConfig.getProxy();
        Proxy proxy = proxyConfig != null ? proxyConfig.toProxy() : null;
        Duration connectTimeout = appConfig.getConnectTimeout();
        Duration readTimeout = appConfig.getReadTimeout();

        appClientFingerprints.compute(appKey, (key, previousFingerprint) -> {
            if (proxy != null || connectTimeout != null || readTimeout != null) {
                AppClientFingerprint currentFingerprint = new AppClientFingerprint(connectTimeout, readTimeout, proxy);
                if (!currentFingerprint.equals(previousFingerprint)) {
                    httpClientAdapter.registerAppRestClient(key, connectTimeout, readTimeout, proxy);
                    log.info("Registered or updated dedicated RestClient for social appKey: [{}], proxy: [{}], timeout: [{}, {}]",
                            key, proxy != null ? proxy.type() : "DIRECT", connectTimeout, readTimeout);
                    return currentFingerprint;
                }
                return previousFingerprint;
            }
            if (previousFingerprint != null) {
                httpClientAdapter.unregisterAppRestClient(key);
                log.info("Evicted dedicated RestClient for social appKey: [{}], reverted to default client", key);
            }
            return null;
        });
    }

    /**
     * 应用级专有网络配置指纹值对象
     */
    private record AppClientFingerprint(Duration connectTimeout, Duration readTimeout, Proxy proxy) {
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
                .pkce(props.isPkce())
                .microsoftTenantId(props.getMicrosoftTenantId())
                .tenantId(props.getTenantId())
                .kid(props.getKid())
                .teamId(props.getTeamId())
                .loginType(props.getLoginType())
                .lang(props.getLang())
                .usertype(props.getUsertype())
                .dingTalkOrgType(props.getDingTalkOrgType())
                .dingTalkCorpId(props.getDingTalkCorpId())
                .dingTalkExclusiveLogin(props.isDingTalkExclusiveLogin())
                .dingTalkExclusiveCorpId(props.getDingTalkExclusiveCorpId())
                .domainPrefix(props.getDomainPrefix())
                .authServerId(props.getAuthServerId())
                .deviceId(props.getDeviceId())
                .clientOsType(props.getClientOsType())
                .packId(props.getPackId())
                .stackOverflowKey(props.getStackOverflowKey())
                .proxy(props.getProxy())
                .connectTimeout(props.getConnectTimeout())
                .readTimeout(props.getReadTimeout())
                .autoSignUp(props.getAutoSignUp())
                .rejectSnapshotUser(props.getRejectSnapshotUser())
                .build();
    }
}
