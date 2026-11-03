package io.github.atengk.boot.sms.config;

import io.github.atengk.boot.sms.properties.SmsProperties;
import io.github.atengk.boot.sms.provider.MockSmsProvider;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.template.SmsTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

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
     * 自动装配顶层短信操作门面
     *
     * @param properties 配置属性
     * @param providers  容器中所有已注册的短信驱动实现
     * @return SmsTemplate
     */
    @Bean
    @ConditionalOnMissingBean
    public SmsTemplate smsTemplate(SmsProperties properties, List<SmsProvider> providers) {
        return new SmsTemplate(properties, providers);
    }
}
