package io.github.atengk.boot.pay.autoconfigure;

import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.core.AlipayTemplate;
import io.github.atengk.boot.pay.core.WechatPayTemplate;
import io.github.atengk.boot.pay.core.certificate.WechatAutoCertificateManager;
import io.github.atengk.boot.pay.core.merchant.NamedMerchantRegistry;
import io.github.atengk.boot.pay.spi.PayCertificateProvider;
import io.github.atengk.boot.pay.util.PayKeyLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
}
