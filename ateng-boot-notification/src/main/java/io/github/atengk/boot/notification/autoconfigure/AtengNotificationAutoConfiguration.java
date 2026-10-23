package io.github.atengk.boot.notification.autoconfigure;

import io.github.atengk.boot.notification.properties.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * ateng-boot 协同通知自动装配入口
 * 统一装配通知核心配置、专有 RestClient 客户端与基础服务设施
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(prefix = "ateng.boot.notification", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional(NotificationConfiguredCondition.class)
@EnableConfigurationProperties(NotificationProperties.class)
public class AtengNotificationAutoConfiguration {

    /**
     * 自动装配专用于 Webhook 发送的 HTTP 请求物理工厂
     * 优先采用 JDK 17 原生 HttpClient 工厂，缺省回退 SimpleClientHttpRequestFactory
     *
     * @param properties 全局通知配置属性
     * @return ClientHttpRequestFactory 物理请求工厂
     */
    @Bean
    @ConditionalOnMissingBean(name = "notificationClientHttpRequestFactory")
    public ClientHttpRequestFactory notificationClientHttpRequestFactory(NotificationProperties properties) {
        Duration connectTimeout = properties.getConnectTimeout() != null ? properties.getConnectTimeout() : Duration.ofSeconds(3);
        Duration readTimeout = properties.getReadTimeout() != null ? properties.getReadTimeout() : Duration.ofSeconds(5);

        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(readTimeout);
            log.debug("Initialized notification ClientHttpRequestFactory using JDK 17 HttpClient (connectTimeout={}, readTimeout={})",
                    connectTimeout, readTimeout);
            return factory;
        } catch (Throwable ex) {
            log.warn("Failed to initialize JdkClientHttpRequestFactory, falling back to SimpleClientHttpRequestFactory: {}", ex.getMessage());
            SimpleClientHttpRequestFactory fallback = new SimpleClientHttpRequestFactory();
            fallback.setConnectTimeout((int) connectTimeout.toMillis());
            fallback.setReadTimeout((int) readTimeout.toMillis());
            return fallback;
        }
    }

    /**
     * 自动装配专用于协同通知的 RestClient 实例
     * 独立绑定连接与读取超时，避免受业务全局 RestClient 干扰
     *
     * @param requestFactory 专用的物理请求工厂
     * @return RestClient 实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "notificationRestClient")
    public RestClient notificationRestClient(
            @Qualifier("notificationClientHttpRequestFactory") ClientHttpRequestFactory requestFactory) {
        log.debug("Initializing dedicated notificationRestClient");
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
