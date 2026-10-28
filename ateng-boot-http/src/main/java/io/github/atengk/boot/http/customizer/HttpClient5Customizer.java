package io.github.atengk.boot.http.customizer;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;

/**
 * Apache HttpClient 5 扩展定制器 SPI
 * 允许调用端以函数式接口注册自定义构建逻辑（如定制连接保持策略、DNS 解析器、Socket 配置等），无需覆盖全量 Bean
 *
 * @author Ateng
 * @since 2026-10-21
 */
@FunctionalInterface
public interface HttpClient5Customizer {

    /**
     * 定制 Apache HttpClient 5 构建器及连接池管理器
     *
     * @param clientBuilder            HttpClient 实例构建器
     * @param connectionManagerBuilder 连接池管理器构建器
     */
    void customize(HttpClientBuilder clientBuilder, PoolingHttpClientConnectionManagerBuilder connectionManagerBuilder);
}
