package io.github.atengk.boot.http.autoconfigure;

import io.github.atengk.boot.http.customizer.HttpClient5Customizer;
import io.github.atengk.boot.http.error.HttpErrorHandler;
import io.github.atengk.boot.http.factory.ClientHttpRequestFactoryBuilder;
import io.github.atengk.boot.http.properties.HttpProperties;
import io.github.atengk.boot.http.template.HttpTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * ateng-boot HTTP 通信底座自动装配类
 * 自动构建双模自适应 ClientHttpRequestFactory、配置 RestClient.Builder 并注册 HttpTemplate 门面 Bean
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(HttpProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.http", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengHttpAutoConfiguration {

    /**
     * 构建底层物理 ClientHttpRequestFactory Bean
     * 依据运行环境自动适配 Apache HttpClient 5 连接池或平滑回退 JDK 17 原生 Client
     *
     * @param properties 配置属性
     * @param httpClient5Customizers 自定义 HttpClient 5 定制器提供者
     * @return ClientHttpRequestFactory 物理请求工厂
     */
    @Bean
    @ConditionalOnMissingBean
    public ClientHttpRequestFactory clientHttpRequestFactory(HttpProperties properties,
                                                             ObjectProvider<HttpClient5Customizer> httpClient5Customizers) {
        return ClientHttpRequestFactoryBuilder.build(properties, httpClient5Customizers.orderedStream().toList());
    }

    /**
     * 注册 HTTP 异常响应统一转译处理器 Bean
     * 拦截 4xx 与 5xx 状态码并统一转译为 HttpRemoteException
     *
     * @return HttpErrorHandler 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpErrorHandler httpErrorHandler() {
        return new HttpErrorHandler();
    }

    /**
     * 自动装配受管的 RestClient.Builder，绑定物理连接工厂与统一异常处理器，并自动应用所有 RestClientCustomizer 定制器
     *
     * @param clientHttpRequestFactory 底层物理连接工厂
     * @param httpErrorHandler HTTP 统一异常响应转译处理器
     * @param customizers RestClient 定制器提供者
     * @return RestClient.Builder
     */
    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder restClientBuilder(ClientHttpRequestFactory clientHttpRequestFactory,
                                                HttpErrorHandler httpErrorHandler,
                                                ObjectProvider<RestClientCustomizer> customizers) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(clientHttpRequestFactory)
                .defaultStatusHandler(httpErrorHandler);
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder;
    }

    /**
     * 注册全局统一命令式操作门面 HttpTemplate Bean
     *
     * @param restClientBuilder RestClient 构建器
     * @return HttpTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpTemplate httpTemplate(RestClient.Builder restClientBuilder) {
        return new HttpTemplate(restClientBuilder);
    }
}
