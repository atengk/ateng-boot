package io.github.atengk.boot.http.registrar;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.lang.reflect.Method;

/**
 * 声明式 HTTP 客户端接口动态代理工厂 Bean
 * 桥接 RestClient 并依托 Spring 6 原生 HttpServiceProxyFactory 与 RestClientAdapter 生成强类型代理实例
 *
 * @param <T> 声明式客户端接口类型
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Getter
@Setter
public class HttpInterfaceFactoryBean<T> implements FactoryBean<T>, ApplicationContextAware, InitializingBean {

    /**
     * 目标客户端接口类型
     */
    private Class<T> interfaceType;

    /**
     * 命名客户端标识
     */
    private String clientName;

    /**
     * 目标服务远程 BaseUrl（支持占位符解析）
     */
    private String url;

    /**
     * Spring 应用上下文
     */
    private ApplicationContext applicationContext;

    /**
     * 生成的动态代理客户端单例实例
     */
    private T client;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        Assert.notNull(this.interfaceType, "interfaceType must not be null");
        Assert.isTrue(this.interfaceType.isInterface(), "interfaceType must be an interface");

        // 1. 解析 BaseUrl（支持环境变量与占位符动态替换）
        String resolvedUrl = resolveUrl();

        // 2. 获取或定制专属于该命名客户端的 RestClient 实例
        RestClient restClient = resolveRestClient(resolvedUrl);

        // 3. 组装 Spring 6 官方 RestClientAdapter 与 HttpServiceProxyFactory 生成代理对象
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory proxyFactory = HttpServiceProxyFactory.builderFor(adapter).build();
        this.client = proxyFactory.createClient(this.interfaceType);

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
            // 1. 尝试从 RestClientRegistry 获取命名客户端
            try {
                Class<?> registryClass = ClassUtils.forName(
                        "io.github.atengk.boot.http.factory.RestClientRegistry",
                        this.applicationContext.getClassLoader()
                );
                Object registry = this.applicationContext.getBeanProvider(registryClass).getIfAvailable();
                if (registry != null) {
                    Method getClientMethod = registryClass.getMethod("getClient", String.class, String.class);
                    Object clientObj = getClientMethod.invoke(registry, this.clientName, this.url);
                    if (clientObj instanceof RestClient customClient) {
                        return customClient;
                    }
                }
            } catch (Throwable ignored) {
                // RestClientRegistry 未就绪时平滑回退
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

}
