package io.github.atengk.boot.http.registrar;

import io.github.atengk.boot.http.annotation.HttpTimeout;
import io.github.atengk.boot.http.context.HttpRequestContextHolder;
import io.github.atengk.boot.http.error.HttpRemoteException;
import io.github.atengk.boot.http.factory.RestClientRegistry;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * 声明式 HTTP 客户端接口动态代理工厂 Bean
 * 桥接 RestClient 并依托 Spring 6 原生 HttpServiceProxyFactory 与 RestClientAdapter 生成强类型代理实例，
 * 并透明增强方法级超时 (@HttpTimeout) 与本地优雅降级 (fallback) 拦截机制
 *
 * @param <T> 声明式客户端接口类型
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Getter
public class HttpInterfaceFactoryBean<T> implements FactoryBean<T>, ApplicationContextAware, InitializingBean {

    /**
     * 目标客户端接口类型
     */
    @Setter
    private Class<T> interfaceType;

    /**
     * 命名客户端标识
     */
    @Setter
    private String clientName;

    /**
     * 目标服务远程 BaseUrl（支持占位符解析）
     */
    @Setter
    private String url;

    /**
     * 声明式本地优雅降级实现类类型
     */
    @Setter
    private Class<?> fallback = void.class;

    /**
     * Spring 应用上下文
     */
    private ApplicationContext applicationContext;

    /**
     * 生成的动态代理客户端单例实例
     */
    private T client;

    /**
     * 方法级别解析后的超时配置缓存
     */
    private final Map<Method, ResolvedTimeout> timeoutCache = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        Assert.notNull(this.interfaceType, "interfaceType must not be null");
        Assert.isTrue(this.interfaceType.isInterface(), "interfaceType must be an interface");

        if (this.fallback != null && this.fallback != void.class) {
            Assert.isTrue(this.interfaceType.isAssignableFrom(this.fallback),
                    () -> "Fallback class [" + this.fallback.getName() + "] for HTTP client [" + this.clientName
                            + "] must implement interface [" + this.interfaceType.getName() + "]");
        }

        // 1. 解析 BaseUrl（支持环境变量与占位符动态替换）
        String resolvedUrl = resolveUrl();

        // 2. 获取或定制专属于该命名客户端的 RestClient 实例
        RestClient restClient = resolveRestClient(resolvedUrl);

        // 3. 组装 Spring 6 官方 RestClientAdapter 与 HttpServiceProxyFactory 生成底层代理对象
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory proxyFactory = HttpServiceProxyFactory.builderFor(adapter).build();
        T rawClient = proxyFactory.createClient(this.interfaceType);

        // 4. 包装增强动态代理，挂载声明式方法级超时 (@HttpTimeout) 与优雅降级 (fallback) 拦截
        this.client = createEnhancedProxy(rawClient);

        log.debug("Created declarative HTTP client proxy for interface [{}] with clientName '{}'",
                this.interfaceType.getName(), this.clientName);
    }

    @Override
    public T getObject() {
        return this.client;
    }

    @Override
    public Class<?> getObjectType() {
        return this.interfaceType;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    /**
     * 构建增强代理，拦截处理方法级超时 (@HttpTimeout) 与优雅降级 (fallback)
     *
     * @param rawClient 底层 HttpServiceProxyFactory 创建的客户端实例
     * @return 增强代理实例
     */
    @SuppressWarnings("unchecked")
    private T createEnhancedProxy(T rawClient) {
        ClassLoader classLoader = this.interfaceType.getClassLoader();
        Class<?>[] interfaces = new Class<?>[]{this.interfaceType};

        return (T) Proxy.newProxyInstance(classLoader, interfaces, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                // 1. 处理 Object 基础方法（依托 Spring ReflectionUtils 精准判定，杜绝接口虚方法穿透发起 HTTP 请求）
                if (org.springframework.util.ReflectionUtils.isEqualsMethod(method)) {
                    return proxy == args[0];
                }
                if (org.springframework.util.ReflectionUtils.isHashCodeMethod(method)) {
                    return System.identityHashCode(proxy);
                }
                if (org.springframework.util.ReflectionUtils.isToStringMethod(method)) {
                    return "HttpInterfaceProxy[" + interfaceType.getName() + ", clientName=" + clientName + "]";
                }

                // 2. 获取并解析当前方法或接口绑定的 @HttpTimeout 超时配置
                ResolvedTimeout timeout = timeoutCache.computeIfAbsent(method, HttpInterfaceFactoryBean.this::resolveTimeout);

                Duration previousReadTimeout = HttpRequestContextHolder.getReadTimeout();
                Duration previousConnectTimeout = HttpRequestContextHolder.getConnectTimeout();
                boolean timeoutApplied = false;

                if (timeout.readTimeout() != null) {
                    HttpRequestContextHolder.setReadTimeout(timeout.readTimeout());
                    timeoutApplied = true;
                }
                if (timeout.connectTimeout() != null) {
                    HttpRequestContextHolder.setConnectTimeout(timeout.connectTimeout());
                    timeoutApplied = true;
                }

                try {
                    try {
                        return method.invoke(rawClient, args);
                    } catch (Throwable ite) {
                        Throwable target = (ite instanceof InvocationTargetException) ? ite.getCause() : ite;
                        if (target == null) {
                            target = ite;
                        }

                        // 3. 尝试本地优雅降级 (fallback)
                        Object fallbackBean = resolveFallbackBean();
                        if (fallbackBean != null && isFallbackEligible(target)) {
                            log.warn("HTTP client [{}] invocation of method [{}] failed ({}), delegating to fallback [{}]",
                                    clientName, method.getName(), target.getClass().getSimpleName() + ": " + target.getMessage(),
                                    fallbackBean.getClass().getName());
                            try {
                                return method.invoke(fallbackBean, args);
                            } catch (InvocationTargetException fallbackIte) {
                                Throwable fallbackTarget = fallbackIte.getCause() != null ? fallbackIte.getCause() : fallbackIte;
                                sneakyThrow(fallbackTarget);
                                return null;
                            }
                        }

                        // 未配置 fallback 或非受管远程异常，保持原始因果链向外抛出
                        sneakyThrow(target);
                        return null;
                    }
                } finally {
                    // 恪守上下文对称清理与现场还原铁律，恢复原有上下文状态，严防线程复用污染
                    if (timeoutApplied) {
                        HttpRequestContextHolder.setReadTimeout(previousReadTimeout);
                        HttpRequestContextHolder.setConnectTimeout(previousConnectTimeout);
                    }
                }
            }
        });
    }

    /**
     * 解析方法或接口上声明的 @HttpTimeout 注解，遵循优先级规则：
     * 方法级 @HttpTimeout > 接口级 @HttpTimeout
     *
     * @param method 待调用的接口方法
     * @return 解析后的超时配置
     */
    private ResolvedTimeout resolveTimeout(Method method) {
        HttpTimeout methodTimeout = AnnotatedElementUtils.findMergedAnnotation(method, HttpTimeout.class);
        HttpTimeout typeTimeout = AnnotatedElementUtils.findMergedAnnotation(this.interfaceType, HttpTimeout.class);
        if (typeTimeout == null && method.getDeclaringClass().isInterface()) {
            typeTimeout = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), HttpTimeout.class);
        }

        String readTimeoutStr = null;
        String connectTimeoutStr = null;

        if (methodTimeout != null) {
            if (StringUtils.hasText(methodTimeout.readTimeout())) {
                readTimeoutStr = methodTimeout.readTimeout();
            } else if (StringUtils.hasText(methodTimeout.value())) {
                readTimeoutStr = methodTimeout.value();
            }
            if (StringUtils.hasText(methodTimeout.connectTimeout())) {
                connectTimeoutStr = methodTimeout.connectTimeout();
            }
        }

        if (typeTimeout != null) {
            if (!StringUtils.hasText(readTimeoutStr)) {
                if (StringUtils.hasText(typeTimeout.readTimeout())) {
                    readTimeoutStr = typeTimeout.readTimeout();
                } else if (StringUtils.hasText(typeTimeout.value())) {
                    readTimeoutStr = typeTimeout.value();
                }
            }
            if (!StringUtils.hasText(connectTimeoutStr)) {
                if (StringUtils.hasText(typeTimeout.connectTimeout())) {
                    connectTimeoutStr = typeTimeout.connectTimeout();
                }
            }
        }

        Duration readTimeout = parseDuration(readTimeoutStr);
        Duration connectTimeout = parseDuration(connectTimeoutStr);
        return new ResolvedTimeout(readTimeout, connectTimeout);
    }

    /**
     * 解析时间配置字符串为 Duration 对象，支持占位符、纯数字毫秒、带量纲单位与 ISO-8601
     *
     * @param raw 原始时间字符串
     * @return 解析后的 Duration 实例，若为空则返回 null
     */
    private Duration parseDuration(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String resolved = raw.trim();
        if (this.applicationContext != null && this.applicationContext.getEnvironment() != null) {
            resolved = this.applicationContext.getEnvironment().resolvePlaceholders(resolved);
        }
        if (!StringUtils.hasText(resolved)) {
            return null;
        }
        resolved = resolved.trim();
        if (resolved.chars().allMatch(Character::isDigit)) {
            return Duration.ofMillis(Long.parseLong(resolved));
        }
        try {
            return DurationStyle.detectAndParse(resolved);
        } catch (Exception ex) {
            return Duration.parse(resolved);
        }
    }

    /**
     * 判定抛出的异常是否属于受管的远程调用故障（网络超时、连接失败、HTTP 4xx/5xx 状态码异常）
     *
     * @param ex 异常实例
     * @return 是否应触发 Fallback 优雅降级
     */
    private boolean isFallbackEligible(Throwable ex) {
        if (ex == null) {
            return false;
        }
        if (isEligibleType(ex)) {
            return true;
        }
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(ex);
        return rootCause != null && isEligibleType(rootCause);
    }

    private boolean isEligibleType(Throwable t) {
        return t instanceof HttpRemoteException
                || t instanceof ResourceAccessException
                || t instanceof RestClientResponseException
                || t instanceof RestClientException
                || t instanceof IOException
                || t instanceof TimeoutException;
    }

    /**
     * 从 Spring 容器中安全解析 Fallback 实现 Bean
     *
     * @return Fallback Bean 实例，若未配置或容器中不存在则返回 null
     */
    private Object resolveFallbackBean() {
        if (this.fallback == null || this.fallback == void.class) {
            return null;
        }
        if (this.applicationContext != null) {
            try {
                Object bean = this.applicationContext.getBeanProvider(this.fallback).getIfAvailable();
                if (bean != null) {
                    return bean;
                }
                // 兜底尝试从接口类型实现中匹配
                Map<String, ?> beans = this.applicationContext.getBeansOfType(this.interfaceType);
                for (Object candidate : beans.values()) {
                    if (this.fallback.isInstance(candidate)) {
                        return candidate;
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to retrieve fallback bean [{}] from ApplicationContext: {}",
                        this.fallback.getName(), ex.getMessage());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * 解析 BaseUrl 占位符
     *
     * @return 解析后的真实 BaseUrl
     */
    private String resolveUrl() {
        if (!StringUtils.hasText(this.url)) {
            return "";
        }
        if (this.applicationContext != null && this.applicationContext.getEnvironment() != null) {
            return this.applicationContext.getEnvironment().resolvePlaceholders(this.url);
        }
        return this.url;
    }

    /**
     * 获取或构建目标 RestClient 实例
     * 优先尝试从容器中的 RestClientRegistry 获取命名客户端单例；若尚未注册则基于 RestClient.Builder 兜底独立构建
     *
     * @param resolvedUrl 解析后的 BaseUrl
     * @return RestClient 实例
     */
    private RestClient resolveRestClient(String resolvedUrl) {
        if (this.applicationContext != null) {
            // 1. 优先从受管的 RestClientRegistry 获取命名客户端单例
            RestClientRegistry registry = this.applicationContext.getBeanProvider(RestClientRegistry.class).getIfAvailable();
            if (registry != null) {
                return registry.getClient(this.clientName, this.url);
            }

            // 2. 从容器中获取受管的 RestClient.Builder 副本
            RestClient.Builder builder = this.applicationContext.getBeanProvider(RestClient.Builder.class)
                    .getIfAvailable(RestClient::builder)
                    .clone();
            if (StringUtils.hasText(resolvedUrl)) {
                builder.baseUrl(resolvedUrl);
            }
            return builder.build();
        }

        // 3. 兜底独立构建
        RestClient.Builder fallbackBuilder = RestClient.builder();
        if (StringUtils.hasText(resolvedUrl)) {
            fallbackBuilder.baseUrl(resolvedUrl);
        }
        return fallbackBuilder.build();
    }

    /**
     * 解析后的单次请求超时配置载荷
     */
    private record ResolvedTimeout(Duration readTimeout, Duration connectTimeout) {
        public boolean hasTimeout() {
            return readTimeout != null || connectTimeout != null;
        }
    }

}
