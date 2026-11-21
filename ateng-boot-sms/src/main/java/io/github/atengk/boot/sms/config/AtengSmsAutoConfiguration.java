package io.github.atengk.boot.sms.config;

import io.github.atengk.boot.sms.callback.SmsCallbackController;
import io.github.atengk.boot.sms.guard.*;
import io.github.atengk.boot.sms.handler.LoggingSmsSendFailureHandler;
import io.github.atengk.boot.sms.handler.SmsSendFailureHandler;
import io.github.atengk.boot.sms.limiter.InMemorySmsRateLimiter;
import io.github.atengk.boot.sms.limiter.RedisSmsRateLimiter;
import io.github.atengk.boot.sms.limiter.SmsRateLimiter;
import io.github.atengk.boot.sms.loadbalancer.SmsChannelLoadBalancer;
import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.AliyunSmsProvider;
import io.github.atengk.boot.sms.provider.BaiduSmsProvider;
import io.github.atengk.boot.sms.provider.ChuanglanSmsProvider;
import io.github.atengk.boot.sms.provider.CloopenSmsProvider;
import io.github.atengk.boot.sms.provider.CtyunSmsProvider;
import io.github.atengk.boot.sms.provider.HuaweiSmsProvider;
import io.github.atengk.boot.sms.provider.JdCloudSmsProvider;
import io.github.atengk.boot.sms.provider.MasSmsProvider;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.NeteaseSmsProvider;
import io.github.atengk.boot.sms.provider.QiniuSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.provider.TencentSmsProvider;
import io.github.atengk.boot.sms.provider.UnismsSmsProvider;
import io.github.atengk.boot.sms.provider.YunpianSmsProvider;
import io.github.atengk.boot.sms.provider.ZhutongSmsProvider;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * ateng-boot 短信底座自动装配类
 *
 * @author Ateng
 * @since 2026-11-02
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass({SmsTemplate.class, RestClient.class})
@ConditionalOnProperty(prefix = "ateng.boot.sms", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SmsProperties.class)
@Import({
        AtengSmsAutoConfiguration.RedisSmsConfiguration.class,
        AtengSmsAutoConfiguration.InMemorySmsConfiguration.class
})
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
     * 自动装配云片网短信驱动 (纯原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return YunpianSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public YunpianSmsProvider yunpianSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new YunpianSmsProvider(builder);
    }

    /**
     * 自动装配天翼云短信驱动 (CT EOP 协议原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return CtyunSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public CtyunSmsProvider ctyunSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new CtyunSmsProvider(builder);
    }

    /**
     * 自动装配百度智能云短信驱动 (BCE Auth v1 原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return BaiduSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public BaiduSmsProvider baiduSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new BaiduSmsProvider(builder);
    }

    /**
     * 自动装配京东云短信驱动 (JDCLOUD2-HMAC-SHA256 原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return JdCloudSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public JdCloudSmsProvider jdCloudSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new JdCloudSmsProvider(builder);
    }

    /**
     * 自动装配七牛云短信驱动 (Qiniu HMAC-SHA1 原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return QiniuSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public QiniuSmsProvider qiniuSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new QiniuSmsProvider(builder);
    }

    /**
     * 自动装配网易云信短信驱动 (SHA-1 CheckSum 原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return NeteaseSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public NeteaseSmsProvider neteaseSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new NeteaseSmsProvider(builder);
    }

    /**
     * 自动装配容联云短信驱动 (MD5 + Base64 原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return CloopenSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public CloopenSmsProvider cloopenSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new CloopenSmsProvider(builder);
    }

    /**
     * 自动装配创蓝 253 短信驱动 (普通/变量双模原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return ChuanglanSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public ChuanglanSmsProvider chuanglanSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new ChuanglanSmsProvider(builder);
    }

    /**
     * 自动装配中国移动云 MAS 短信驱动 (Base64 + MD5 MAC 原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return MasSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public MasSmsProvider masSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new MasSmsProvider(builder);
    }

    /**
     * 自动装配合一短信驱动 (HMAC-SHA256 / SimpleMode 无状态原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return UnismsSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public UnismsSmsProvider unismsSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new UnismsSmsProvider(builder);
    }

    /**
     * 自动装配助通科技短信驱动 (双重 MD5 模板/纯文本双模原生直连)
     *
     * @param restClientBuilderProvider RestClient 构造器提供者
     * @return ZhutongSmsProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public ZhutongSmsProvider zhutongSmsProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        RestClient.Builder builder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
        return new ZhutongSmsProvider(builder);
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
     * 自动装配短信渠道健康度短时熔断器
     *
     * @param properties    配置属性
     * @param clockProvider 可选时钟提供者
     * @return SmsChannelCircuitBreaker
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsChannelCircuitBreaker smsChannelCircuitBreaker(SmsProperties properties,
                                                             ObjectProvider<Clock> clockProvider) {
        return new SmsChannelCircuitBreaker(properties.getGuard(), clockProvider.getIfAvailable(Clock::systemDefaultZone));
    }

    /**
     * 自动装配平滑加权轮询负载均衡器
     *
     * @param channelRegistry 渠道注册中心
     * @param circuitBreaker  渠道健康度熔断器
     * @return SmsChannelLoadBalancer
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsChannelLoadBalancer smsChannelLoadBalancer(SmsChannelRegistry channelRegistry,
                                                         SmsChannelCircuitBreaker circuitBreaker) {
        return new SmsChannelLoadBalancer(channelRegistry, circuitBreaker);
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
     * @param circuitBreakerProvider   渠道熔断器提供者
     * @param loadBalancerProvider     负载均衡器提供者
     * @param idempotentGuardProvider  出站幂等卫士提供者
     * @param eventPublisherProvider   事件发布器提供者
     * @param taskExecutorProvider     异步执行器提供者
     * @param clockProvider            可选时钟提供者
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
                                   ObjectProvider<SmsChannelCircuitBreaker> circuitBreakerProvider,
                                   ObjectProvider<SmsChannelLoadBalancer> loadBalancerProvider,
                                   ObjectProvider<SmsIdempotentGuard> idempotentGuardProvider,
                                   ObjectProvider<ApplicationEventPublisher> eventPublisherProvider,
                                   ObjectProvider<Executor> taskExecutorProvider,
                                   ObjectProvider<Clock> clockProvider) {
        return new SmsTemplate(
                properties,
                providers,
                channelRegistry,
                channelResolversProvider.getIfAvailable(),
                failureHandlersProvider.getIfAvailable(),
                blacklistProvider.getIfAvailable(),
                budgetBreaker.getIfAvailable(),
                circuitBreakerProvider.getIfAvailable(),
                loadBalancerProvider.getIfAvailable(),
                idempotentGuardProvider.getIfAvailable(),
                eventPublisherProvider.getIfAvailable(),
                taskExecutorProvider.getIfAvailable(),
                clockProvider.getIfAvailable(Clock::systemDefaultZone)
        );
    }

    /**
     * 自动装配入站 Webhook 回调网关控制器
     *
     * @param channelRegistry   渠道注册中心
     * @param eventPublisher    领域事件发布器
     * @param blacklistProvider 出站黑名单过滤器提供者
     * @param properties        配置属性
     * @return SmsCallbackController
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.sms.callback", name = "enabled", havingValue = "true")
    public SmsCallbackController smsCallbackController(SmsChannelRegistry channelRegistry,
                                                       ApplicationEventPublisher eventPublisher,
                                                       ObjectProvider<SmsBlacklistProvider> blacklistProvider,
                                                       SmsProperties properties) {
        return new SmsCallbackController(channelRegistry, eventPublisher, blacklistProvider.getIfAvailable(), properties);
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

    /**
     * Redis 分布式基础设施自动装配配置类
     * 仅当类路径存在 StringRedisTemplate 且容器中已声明该 Bean 时激活
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    @ConditionalOnBean(type = "org.springframework.data.redis.core.StringRedisTemplate")
    static class RedisSmsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public DailyBudgetBreaker redisDailyBudgetBreaker(SmsProperties properties,
                                                          StringRedisTemplate redisTemplate,
                                                          ObjectProvider<ApplicationEventPublisher> eventPublisherProvider,
                                                          ObjectProvider<Clock> clockProvider) {
            return new RedisDailyBudgetBreaker(redisTemplate, properties.getGuard(),
                    eventPublisherProvider.getIfAvailable(), clockProvider.getIfAvailable(Clock::systemDefaultZone));
        }

        @Bean
        @ConditionalOnMissingBean
        public SmsRateLimiter redisSmsRateLimiter(SmsProperties properties, StringRedisTemplate redisTemplate) {
            return new RedisSmsRateLimiter(redisTemplate, properties.getRateLimit());
        }

        @Bean
        @ConditionalOnMissingBean
        public SmsVerifyCodeRepository redisSmsVerifyCodeRepository(StringRedisTemplate redisTemplate,
                                                                    ObjectProvider<Clock> clockProvider) {
            return new RedisSmsVerifyCodeRepository(redisTemplate, clockProvider.getIfAvailable(Clock::systemDefaultZone));
        }

        @Bean
        @ConditionalOnMissingBean
        public SmsBlacklistProvider redisSmsBlacklistProvider(SmsProperties properties, StringRedisTemplate redisTemplate) {
            return new RedisSmsBlacklistProvider(redisTemplate, properties.getGuard());
        }

        @Bean
        @ConditionalOnMissingBean
        public SmsIdempotentGuard redisSmsIdempotentGuard(StringRedisTemplate redisTemplate) {
            return new RedisSmsIdempotentGuard(redisTemplate);
        }
    }

    /**
     * 纯内存自洽能力自动装配配置类
     * 在未接入 Redis 或 StringRedisTemplate 不存在时优雅降级保底，确保 100% 离线自洽
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(type = "org.springframework.data.redis.core.StringRedisTemplate")
    static class InMemorySmsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public DailyBudgetBreaker inMemoryDailyBudgetBreaker(SmsProperties properties,
                                                            ObjectProvider<ApplicationEventPublisher> eventPublisherProvider,
                                                            ObjectProvider<Clock> clockProvider) {
            return new InMemoryDailyBudgetBreaker(properties.getGuard(), eventPublisherProvider.getIfAvailable(),
                    clockProvider.getIfAvailable(Clock::systemDefaultZone));
        }

        @Bean
        @ConditionalOnMissingBean
        public SmsRateLimiter inMemorySmsRateLimiter(SmsProperties properties,
                                                     ObjectProvider<Clock> clockProvider) {
            return new InMemorySmsRateLimiter(properties.getRateLimit(),
                    properties.getGuard().getInMemoryMaxEntries(),
                    clockProvider.getIfAvailable(Clock::systemDefaultZone));
        }

        @Bean
        @ConditionalOnMissingBean
        public SmsVerifyCodeRepository inMemorySmsVerifyCodeRepository(SmsProperties properties,
                                                                       ObjectProvider<Clock> clockProvider) {
            return new InMemorySmsVerifyCodeRepository(properties.getGuard().getInMemoryMaxEntries(),
                    clockProvider.getIfAvailable(Clock::systemDefaultZone));
        }

        @Bean
        @ConditionalOnMissingBean
        public SmsBlacklistProvider inMemorySmsBlacklistProvider(SmsProperties properties) {
            return new InMemorySmsBlacklistProvider(properties.getGuard().getBlacklist());
        }

        @Bean
        @ConditionalOnMissingBean
        public SmsIdempotentGuard inMemorySmsIdempotentGuard(SmsProperties properties,
                                                             ObjectProvider<Clock> clockProvider) {
            return new InMemorySmsIdempotentGuard(properties.getGuard().getInMemoryMaxEntries(),
                    clockProvider.getIfAvailable(Clock::systemDefaultZone));
        }
    }
}
