package io.github.atengk.boot.http.factory;

import io.github.atengk.boot.http.customizer.HttpClient5Customizer;
import io.github.atengk.boot.http.properties.HttpEngineType;
import io.github.atengk.boot.http.properties.HttpProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.ClassUtils;

import java.util.List;

/**
 * 物理连接请求工厂自适应构建门面
 * 依据配置引擎类型（engine）及运行时类路径自适应构建 Apache HttpClient 5 连接池工厂或 JDK 17 原生 Client 工厂
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public final class ClientHttpRequestFactoryBuilder {

    private static final String HTTPCLIENT5_CLASS = "org.apache.hc.client5.http.impl.classic.CloseableHttpClient";

    private ClientHttpRequestFactoryBuilder() {
    }

    /**
     * 构建适合当前环境与配置的 ClientHttpRequestFactory
     *
     * @param properties 配置属性
     * @param customizers HttpClient 5 自定义定制器列表
     * @return ClientHttpRequestFactory 物理请求工厂
     */
    public static ClientHttpRequestFactory build(HttpProperties properties, List<HttpClient5Customizer> customizers) {
        HttpEngineType engine = properties.getEngine();
        boolean hasHttpClient5 = ClassUtils.isPresent(HTTPCLIENT5_CLASS, ClientHttpRequestFactoryBuilder.class.getClassLoader());

        if (engine == HttpEngineType.HTTPCLIENT5) {
            if (!hasHttpClient5) {
                throw new IllegalStateException("配置了 ateng.boot.http.engine=httpclient5，但类路径中未引入 Apache HttpClient 5 依赖");
            }
            return ApacheHttpClient5Factory.createFactory(properties, customizers);
        }

        if (engine == HttpEngineType.JDK) {
            return JdkHttpClientFactory.createFactory(properties);
        }

        // AUTO 模式：优先装配 HttpClient 5 连接池，缺类平滑回退 JDK 17
        if (hasHttpClient5) {
            return ApacheHttpClient5Factory.createFactory(properties, customizers);
        } else {
            log.warn("检测到类路径未引入 Apache HttpClient 5 (缺少 {})，自动平滑降级为 JDK 17 原生 HttpClient", HTTPCLIENT5_CLASS);
            return JdkHttpClientFactory.createFactory(properties);
        }
    }
}
