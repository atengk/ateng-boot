package io.github.atengk.boot.pay.autoconfigure;

import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.controller.PayWebhookController;
import io.github.atengk.boot.pay.core.AlipayTemplate;
import io.github.atengk.boot.pay.core.PayTemplate;
import io.github.atengk.boot.pay.core.WechatPayTemplate;
import io.github.atengk.boot.pay.core.callback.PayCallbackHandler;
import io.github.atengk.boot.pay.core.certificate.WechatAutoCertificateManager;
import io.github.atengk.boot.pay.core.lock.InMemoryPayLockManager;
import io.github.atengk.boot.pay.core.lock.PayLockManager;
import io.github.atengk.boot.pay.core.lock.RedisPayLockManager;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.core.profitsharing.WechatProfitSharingService;
import io.github.atengk.boot.pay.core.transfer.PayTransferService;
import io.github.atengk.boot.pay.spi.PayCallbackListener;
import io.github.atengk.boot.pay.spi.PayCertificateProvider;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.List;

/**
 * 支付模块顶层统一自动装配类
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@EnableConfigurationProperties(PayProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.pay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengPayAutoConfiguration {

    public AtengPayAutoConfiguration(ObjectProvider<PayCertificateProvider> certificateProviders) {
        log.info("Initialized AtengPayAutoConfiguration for enterprise multi-channel payment foundation.");
        certificateProviders.orderedStream().forEach(PayKeyLoader::registerProvider);
    }

    /**
     * 注册命名多商户与多应用注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public NamedMerchantRegistry namedMerchantRegistry(PayProperties payProperties) {
        return new NamedMerchantRegistry(payProperties);
    }

    /**
     * 注册微信平台证书自动轮换与生命周期管理器
     */
    @Bean
    @ConditionalOnMissingBean
    public WechatAutoCertificateManager wechatAutoCertificateManager() {
        return new WechatAutoCertificateManager();
    }

    /**
     * 注册微信支付专有收单与高阶执行门面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.pay.wechat", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WechatPayTemplate wechatPayTemplate(NamedMerchantRegistry namedMerchantRegistry,
                                               WechatAutoCertificateManager wechatAutoCertificateManager,
                                               PayProperties payProperties) {
        return new WechatPayTemplate(namedMerchantRegistry, wechatAutoCertificateManager, payProperties);
    }

    /**
     * 注册微信分账专有独立业务服务（由物理开关 ateng.boot.pay.profit-sharing.enabled 守卫）
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(WechatPayTemplate.class)
    @ConditionalOnProperty(name = "ateng.boot.pay.profit-sharing.enabled", havingValue = "true")
    public WechatProfitSharingService wechatProfitSharingService(WechatPayTemplate wechatPayTemplate) {
        log.info("Profit-sharing feature enabled, configuring WechatProfitSharingService.");
        return new WechatProfitSharingService(wechatPayTemplate);
    }

    /**
     * 注册支付宝专有收单与高阶执行门面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.pay.alipay", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AlipayTemplate alipayTemplate(NamedMerchantRegistry namedMerchantRegistry, PayProperties payProperties) {
        return new AlipayTemplate(namedMerchantRegistry, payProperties);
    }

    /**
     * 注册顶层轻量统一收单门面
     */
    @Bean
    @ConditionalOnMissingBean
    public PayTemplate payTemplate(ObjectProvider<WechatPayTemplate> wechatPayTemplateProvider,
                                   ObjectProvider<AlipayTemplate> alipayTemplateProvider) {
        return new PayTemplate(wechatPayTemplateProvider.getIfAvailable(), alipayTemplateProvider.getIfAvailable());
    }

    /**
     * 注册单笔资金转账专有业务服务（由物理开关 ateng.boot.pay.transfer.enabled 守卫）
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PayTemplate.class)
    @ConditionalOnProperty(name = "ateng.boot.pay.transfer.enabled", havingValue = "true")
    public PayTransferService payTransferService(PayTemplate payTemplate) {
        log.info("Single fund transfer feature enabled, configuring PayTransferService.");
        return new PayTransferService(payTemplate);
    }

    /**
     * Redis 分布式防重锁自动装配
     */
    @Slf4j
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    public static class RedisPayLockConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(PayLockManager.class)
        public PayLockManager redisPayLockManager(StringRedisTemplate redisTemplate) {
            log.info("Redis infrastructure detected, configuring RedisPayLockManager for distributed payment callback locks.");
            return new RedisPayLockManager(redisTemplate);
        }
    }

    /**
     * 注册本地单机防重并发锁保底（当无 Redis 或未配置 StringRedisTemplate Bean 时生效）
     */
    @Bean
    @ConditionalOnMissingBean(PayLockManager.class)
    public PayLockManager inMemoryPayLockManager() {
        log.info("No Redis infrastructure detected, fallback to InMemoryPayLockManager for local payment callback locks.");
        return new InMemoryPayLockManager();
    }

    /**
     * 注册支付 Webhook 异步回调处理门面
     */
    @Bean
    @ConditionalOnMissingBean
    public PayCallbackHandler payCallbackHandler(NamedMerchantRegistry namedMerchantRegistry,
                                                ObjectProvider<WechatAutoCertificateManager> wechatAutoCertificateManagerProvider,
                                                PayLockManager payLockManager,
                                                PayProperties payProperties,
                                                ObjectProvider<List<PayCallbackListener>> listenersProvider) {
        List<PayCallbackListener> listeners = listenersProvider.getIfAvailable(Collections::emptyList);
        return new PayCallbackHandler(namedMerchantRegistry,
                wechatAutoCertificateManagerProvider.getIfAvailable(),
                payLockManager,
                payProperties,
                listeners);
    }

    /**
     * 注册统一支付 Webhook 回调端点控制器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "ateng.boot.pay.callback", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PayWebhookController payWebhookController(PayCallbackHandler payCallbackHandler) {
        return new PayWebhookController(payCallbackHandler);
    }
}
