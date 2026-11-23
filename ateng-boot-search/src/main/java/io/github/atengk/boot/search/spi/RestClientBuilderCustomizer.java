package io.github.atengk.boot.search.spi;

import io.github.atengk.boot.search.properties.AtengSearchProperties;
import org.elasticsearch.client.RestClientBuilder;

/**
 * 底层 RestClientBuilder 定制扩展点 SPI
 * <p>
 * 允许下游应用在不覆盖默认 {@link SearchEngineClient} 自动装配的前提下，
 * 按 Spring {@code @Order} 顺序有界定制连接池、默认请求头、回调或重试策略。
 * <p>
 * <strong>注意：</strong>{@link RestClientBuilder} 的 {@code setDefaultHeaders}、
 * {@code setRequestConfigCallback}、{@code setHttpClientConfigCallback} 属于覆盖式配置，
 * 定制时应避免无意覆盖驱动默认注入的鉴权头、超时、连接池或 SSL 上下文。
 *
 * @author Ateng
 * @since 2026-11-20
 */
@FunctionalInterface
public interface RestClientBuilderCustomizer {

    /**
     * 定制底层 {@link RestClientBuilder} 实例
     *
     * @param builder    RestClient 构建器
     * @param properties 当前搜索模块配置属性
     */
    void customize(RestClientBuilder builder, AtengSearchProperties properties);
}
