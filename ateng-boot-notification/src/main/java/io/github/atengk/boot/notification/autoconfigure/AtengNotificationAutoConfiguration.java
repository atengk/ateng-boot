package io.github.atengk.boot.notification.autoconfigure;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelProvider;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.channel.NotificationChannelResolver;
import io.github.atengk.boot.notification.properties.NotificationProperties;
import io.github.atengk.boot.notification.provider.DingTalkNotificationProvider;
import io.github.atengk.boot.notification.provider.FeishuNotificationProvider;
import io.github.atengk.boot.notification.provider.GenericWebhookNotificationProvider;
import io.github.atengk.boot.notification.provider.WeComNotificationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.List;

/**
 * ateng-boot 协同通知自动装配入口
 * 统一装配通知核心配置、专有 RestClient 客户端、渠道注册中心与平台驱动引擎
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

    /**
     * 自动装配多命名通知渠道注册中心
     * 从配置属性预热加载 channels，并挂载可选的 NotificationChannelProvider 与 NotificationChannelResolver SPI
     *
     * @param properties        全局通知配置属性
     * @param providersProvider 可选的外部渠道供给器 SPI
     * @param resolversProvider 可选的动态渠道解析器 SPI
     * @return NotificationChannelRegistry 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public NotificationChannelRegistry notificationChannelRegistry(
            NotificationProperties properties,
            ObjectProvider<List<NotificationChannelProvider>> providersProvider,
            ObjectProvider<List<NotificationChannelResolver>> resolversProvider) {
        NotificationChannelRegistry registry = new NotificationChannelRegistry(properties.getDefaultChannel());

        if (properties.getChannels() != null) {
            properties.getChannels().forEach((name, channelProps) -> {
                if (channelProps != null && channelProps.getPlatform() != null && channelProps.getWebhook() != null) {
                    registry.register(NotificationChannel.fromProperties(name, channelProps));
                }
            });
        }

        providersProvider.ifAvailable(providers -> providers.forEach(registry::addProvider));
        resolversProvider.ifAvailable(resolvers -> resolvers.forEach(registry::addResolver));

        log.debug("Initialized NotificationChannelRegistry with {} pre-configured channels (defaultChannel={})",
                registry.size(), properties.getDefaultChannel());
        return registry;
    }

    /**
     * 自动装配钉钉平台通知驱动
     *
     * @return DingTalkNotificationProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public DingTalkNotificationProvider dingTalkNotificationProvider() {
        return new DingTalkNotificationProvider();
    }

    /**
     * 自动装配企业微信平台通知驱动
     *
     * @return WeComNotificationProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public WeComNotificationProvider weComNotificationProvider() {
        return new WeComNotificationProvider();
    }

    /**
     * 自动装配飞书平台通知驱动
     *
     * @return FeishuNotificationProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public FeishuNotificationProvider feishuNotificationProvider() {
        return new FeishuNotificationProvider();
    }

    /**
     * 自动装配通用 Webhook 平台通知驱动
     *
     * @return GenericWebhookNotificationProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public GenericWebhookNotificationProvider genericWebhookNotificationProvider() {
        return new GenericWebhookNotificationProvider();
    }
}

