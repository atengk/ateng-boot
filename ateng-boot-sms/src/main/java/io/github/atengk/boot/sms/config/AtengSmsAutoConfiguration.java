package io.github.atengk.boot.sms.config;

import io.github.atengk.boot.sms.callback.SmsCallbackController;
import io.github.atengk.boot.sms.guard.*;
import io.github.atengk.boot.sms.handler.LoggingSmsSendFailureHandler;
import io.github.atengk.boot.sms.handler.SmsSendFailureHandler;
import io.github.atengk.boot.sms.limiter.InMemorySmsRateLimiter;
import io.github.atengk.boot.sms.limiter.RedisSmsRateLimiter;
import io.github.atengk.boot.sms.limiter.SmsRateLimiter;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.AliyunSmsProvider;
import io.github.atengk.boot.sms.provider.HuaweiSmsProvider;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.provider.TencentSmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.registry.SmsChannelResolver;
import io.github.atengk.boot.sms.registry.TenantSmsChannelResolver;
import io.github.atengk.boot.sms.repository.InMemorySmsVerifyCodeRepository;
import io.github.atengk.boot.sms.repository.RedisSmsVerifyCodeRepository;
import io.github.atengk.boot.sms.repository.SmsVerifyCodeRepository;
import io.github.atengk.boot.sms.template.SmsTemplate;
import io.github.atengk.boot.sms.template.SmsVerifyCodeTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * ateng-boot 短信底座自动装配类
 *
 * @author Ateng
 * @since 2026-11-02
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ateng.boot.sms", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SmsProperties.class)
public class AtengSmsAutoConfiguration {

    /**
     * 自动装配 Mock 测试沙箱短信驱动
     *
     * @return MockSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public MockSmsProvider mockSmsProvider() {
        return new MockSmsProvider();
    }

    /**
     * 自动装配阿里云短信驱动 (POP RPC 协议原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return AliyunSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public AliyunSmsProvider aliyunSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new AliyunSmsProvider(builder);
    }

    /**
     * 自动装配腾讯云短信驱动 (API 3.0 TC3 协议原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return TencentSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public TencentSmsProvider tencentSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new TencentSmsProvider(builder);
    }

    /**
     * 自动装配华为云短信驱动 (WSSE 鉴权协议原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return HuaweiSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public HuaweiSmsProvider huaweiSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new HuaweiSmsProvider(builder);
    }

    /**
     * 自动装配多命名短信渠道注册中心
     *
     * @param properties 配置属性
     * @return SmsChannelRegistry
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsChannelRegistry smsChannelRegistry(SmsProperties properties) {
        return new SmsChannelRegistry(properties);
    }

    /**
     * 自动装配基于多租户上下文的默认短信渠道解析器
     *
     * @param channelRegistry 渠道注册表
     * @return TenantSmsChannelResolver
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantSmsChannelResolver tenantSmsChannelResolver(SmsChannelRegistry channelRegistry) {
        return new TenantSmsChannelResolver(channelRegistry);
    }

    /**
     * 自动装配手机号出站黑名单过滤器
     *
     * @return SmsBlacklistProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsBlacklistProvider smsBlacklistProvider() {
        return new InMemorySmsBlacklistProvider();
    }

    /**
     * 自动装配单日成本预算熔断器（检测到 Redis 时采用分布式熔断计数，否则优雅降级为纯内存实现）
     *
     * @param properties             配置属性
     * @param redisTemplateProvider  Redis 模板提供者
     * @param eventPublisherProvider 事件发布器提供者
     * @return DailyBudgetBreaker
     */
    @Bean
    @ConditionalOnMissingBean
    public DailyBudgetBreaker dailyBudgetBreaker(SmsProperties properties,
                                                 ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                                 ObjectProvider<ApplicationEventPublisher> eventPublisherProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        ApplicationEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        if (redisTemplate != null) {
            return new RedisDailyBudgetBreaker(redisTemplate, properties.getGuard(), publisher);
        }
        return new InMemoryDailyBudgetBreaker(properties.getGuard(), publisher);
    }

    /**
     * 自动装配默认发信失败后置补偿处理器
     *
     * @return SmsSendFailureHandler
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsSendFailureHandler loggingSmsSendFailureHandler() {
        return new LoggingSmsSendFailureHandler();
    }

    /**
     * 自动装配顶层短信操作门面
     *
     * @param properties               配置属性
     * @param providers                容器中所有已注册的短信驱动实现
     * @param channelRegistry          渠道注册中心
     * @param channelResolversProvider 渠道解析器提供者列表
     * @param failureHandlersProvider  失败补偿处理器列表提供者
     * @param blacklistProvider        出站黑名单过滤器提供者
     * @param budgetBreaker            单日成本预算熔断器提供者
     * @param taskExecutorProvider     异步执行器提供者
     * @return SmsTemplate
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsTemplate smsTemplate(SmsProperties properties,
                                   List<SmsProvider> providers,
                                   SmsChannelRegistry channelRegistry,
                                   ObjectProvider<List<SmsChannelResolver>> channelResolversProvider,
                                   ObjectProvider<List<SmsSendFailureHandler>> failureHandlersProvider,
                                   ObjectProvider<SmsBlacklistProvider> blacklistProvider,
                                   ObjectProvider<DailyBudgetBreaker> budgetBreaker,
                                   ObjectProvider<Executor> taskExecutorProvider) {
        return new SmsTemplate(
                properties,
                providers,
                channelRegistry,
                channelResolversProvider.getIfAvailable(),
                failureHandlersProvider.getIfAvailable(),
                blacklistProvider.getIfAvailable(),
                budgetBreaker.getIfAvailable(),
                taskExecutorProvider.getIfAvailable()
        );
    }

    /**
     * 自动装配入站 Webhook 回调网关控制器
     *
     * @param channelRegistry 渠道注册中心
     * @param eventPublisher  领域事件发布器
     * @return SmsCallbackController
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.sms.callback", name = "enabled", havingValue = "true")
    public SmsCallbackController smsCallbackController(SmsChannelRegistry channelRegistry,
                                                       ApplicationEventPublisher eventPublisher) {
        return new SmsCallbackController(channelRegistry, eventPublisher);
    }

    /**
     * 自动装配滑动窗口频控限流器（检测到 Redis 时采用分布式限流，否则优雅降级为纯内存限流）
     *
     * @param properties            配置属性
     * @param redisTemplateProvider Redis 模板提供者
     * @return SmsRateLimiter
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsRateLimiter smsRateLimiter(SmsProperties properties,
                                         ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            return new RedisSmsRateLimiter(redisTemplate, properties.getRateLimit());
        }
        return new InMemorySmsRateLimiter(properties.getRateLimit());
    }

    /**
     * 自动装配短信验证码仓储（检测到 Redis 时采用分布式原子仓储，否则优雅降级为本地内存仓储）
     *
     * @param redisTemplateProvider Redis 模板提供者
     * @return SmsVerifyCodeRepository
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsVerifyCodeRepository smsVerifyCodeRepository(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            return new RedisSmsVerifyCodeRepository(redisTemplate);
        }
        return new InMemorySmsVerifyCodeRepository();
    }

    /**
     * 自动装配短信验证码全生命周期治理门面
     *
     * @param smsTemplate 顶层发信门面
     * @param properties  配置属性
     * @param rateLimiter 频控限流器
     * @param repository  验证码仓储
     * @return SmsVerifyCodeTemplate
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsVerifyCodeTemplate smsVerifyCodeTemplate(SmsTemplate smsTemplate,
                                                       SmsProperties properties,
                                                       SmsRateLimiter rateLimiter,
                                                       SmsVerifyCodeRepository repository) {
        return new SmsVerifyCodeTemplate(smsTemplate, properties, rateLimiter, repository);
    }
}
