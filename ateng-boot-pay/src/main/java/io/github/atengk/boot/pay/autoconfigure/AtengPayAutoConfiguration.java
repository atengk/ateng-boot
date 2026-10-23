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
import io.github.atengk.boot.pay.spi.PayCallbackListener;
import io.github.atengk.boot.pay.spi.PayCertificateProvider;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
                                               WechatAutoCertificateManager wechatAutoCertificateManager) {
        return new WechatPayTemplate(namedMerchantRegistry, wechatAutoCertificateManager);
    }

    /**
     * 注册支付宝专有收单与高阶执行门面
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.pay.alipay", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AlipayTemplate alipayTemplate(NamedMerchantRegistry namedMerchantRegistry) {
        return new AlipayTemplate(namedMerchantRegistry);
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
     * 注册双轨支付防重并发锁管理器（Redis 优先，内存锁保底）
     */
    @Bean
    @ConditionalOnMissingBean(PayLockManager.class)
    public PayLockManager payLockManager(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            log.info("Redis infrastructure detected, configuring RedisPayLockManager for distributed payment callback locks.");
            return new RedisPayLockManager(redisTemplate);
        }
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
