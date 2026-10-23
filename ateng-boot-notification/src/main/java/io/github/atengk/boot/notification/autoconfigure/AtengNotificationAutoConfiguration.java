package io.github.atengk.boot.notification.autoconfigure;

import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelProvider;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.channel.NotificationChannelResolver;
import io.github.atengk.boot.notification.handler.LoggingNotificationSendFailureHandler;
import io.github.atengk.boot.notification.handler.NotificationSendFailureHandler;
import io.github.atengk.boot.notification.limiter.InMemoryNotificationRateLimiter;
import io.github.atengk.boot.notification.limiter.NotificationRateLimiter;
import io.github.atengk.boot.notification.properties.NotificationProperties;
import io.github.atengk.boot.notification.provider.DingTalkNotificationProvider;
import io.github.atengk.boot.notification.provider.FeishuNotificationProvider;
import io.github.atengk.boot.notification.provider.GenericWebhookNotificationProvider;
import io.github.atengk.boot.notification.provider.NotificationProvider;
import io.github.atengk.boot.notification.provider.WeComNotificationProvider;
import io.github.atengk.boot.notification.renderer.NotificationTemplateRenderer;
import io.github.atengk.boot.notification.renderer.SimpleNotificationTemplateRenderer;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

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
     * 自动装配客户端通道频控器
     * 默认装配单机内存滑动窗口频控器 InMemoryNotificationRateLimiter
     * 预留分布式扩展钩子，当注入第三方分布式实现或自定义 Bean 时自动优雅退让
     *
     * @param properties 全局通知配置属性
     * @return NotificationRateLimiter 实例
     */
    @Bean
    @ConditionalOnMissingBean(NotificationRateLimiter.class)
    public NotificationRateLimiter notificationRateLimiter(NotificationProperties properties) {
        if (properties.getRateLimit().isDistributed()) {
            log.warn("协同通知频控配置启用了 distributed=true，但未检测到分布式 NotificationRateLimiter Bean，回退为 InMemoryNotificationRateLimiter 单机内存频控");
        }
        return new InMemoryNotificationRateLimiter(properties.getRateLimit());
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

    /**
     * 自动装配通知模板动态渲染引擎
     * 默认提供纯原生轻量占位符插值渲染器 SimpleNotificationTemplateRenderer
     *
     * @return NotificationTemplateRenderer 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public NotificationTemplateRenderer notificationTemplateRenderer() {
        return new SimpleNotificationTemplateRenderer();
    }

    /**
     * 自动装配发信失败补偿处理器 SPI
     * 默认提供基于 SLF4J 记录错误日志的 LoggingNotificationSendFailureHandler
     *
     * @return NotificationSendFailureHandler 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public NotificationSendFailureHandler notificationSendFailureHandler() {
        return new LoggingNotificationSendFailureHandler();
    }

    /**
     * 自动装配协同通知操作核心门面 NotificationTemplate
     * 统一集成配置中心、渠道注册表、全驱动引擎、滑动窗口频控器、受管线程池与生命周期事件发布
     *
     * @param properties             全局通知配置属性
     * @param channelRegistry        多命名渠道注册中心
     * @param providers              平台驱动引擎列表
     * @param rateLimiter            客户端通道频控器
     * @param restClient             专有 RestClient 客户端
     * @param templateRenderer       模板动态渲染引擎
     * @param failureHandler         发信失败补偿处理器
     * @param eventPublisherProvider 可选的 Spring 事件发布器
     * @param asyncExecutorProvider  可选的受管异步任务执行器
     * @return NotificationTemplate 统一门面实例
     */
    @Bean
    @ConditionalOnMissingBean
    public NotificationTemplate notificationTemplate(
            NotificationProperties properties,
            NotificationChannelRegistry channelRegistry,
            List<NotificationProvider> providers,
            NotificationRateLimiter rateLimiter,
            @Qualifier("notificationRestClient") RestClient restClient,
            NotificationTemplateRenderer templateRenderer,
            NotificationSendFailureHandler failureHandler,
            ObjectProvider<ApplicationEventPublisher> eventPublisherProvider,
            @Qualifier("taskExecutor") ObjectProvider<Executor> asyncExecutorProvider) {
        log.debug("Initializing NotificationTemplate facade (defaultChannel={})", properties.getDefaultChannel());
        return new NotificationTemplate(
                properties,
                channelRegistry,
                providers,
                rateLimiter,
                restClient,
                templateRenderer,
                failureHandler,
                eventPublisherProvider.getIfAvailable(),
                asyncExecutorProvider.getIfAvailable()
        );
    }
}

