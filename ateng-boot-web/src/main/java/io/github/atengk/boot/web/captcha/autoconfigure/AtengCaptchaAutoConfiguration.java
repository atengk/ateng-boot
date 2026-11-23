package io.github.atengk.boot.web.captcha.autoconfigure;

import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;
import io.github.atengk.boot.web.captcha.renderer.CaptchaRenderer;
import io.github.atengk.boot.web.captcha.renderer.PngCaptchaRenderer;
import io.github.atengk.boot.web.captcha.renderer.SvgCaptchaRenderer;
import io.github.atengk.boot.web.captcha.store.CaptchaStore;
import io.github.atengk.boot.web.captcha.store.InMemoryCaptchaStore;
import io.github.atengk.boot.web.captcha.store.RedisCaptchaStore;
import io.github.atengk.boot.web.captcha.store.RedissonCaptchaStore;
import io.github.atengk.boot.web.captcha.template.CaptchaTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.Executor;

/**
 * 图形验证码三级自适应自动装配类
 * 构建 Tier 1(Redisson) -> Tier 2(Redis) -> Tier 3(InMemory) 存储分级，并装配渲染引擎与 {@link CaptchaTemplate} 门面
 *
 * @author Ateng
 * @since 2026-11-17
 */
@AutoConfiguration(afterName = {
        "org.redisson.spring.starter.RedissonAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@EnableConfigurationProperties(CaptchaProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.web.captcha", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({
        AtengCaptchaAutoConfiguration.RedissonCaptchaConfiguration.class,
        AtengCaptchaAutoConfiguration.RedisCaptchaConfiguration.class,
        AtengCaptchaAutoConfiguration.InMemoryCaptchaConfiguration.class
})
public class AtengCaptchaAutoConfiguration {

    /**
     * Tier 1: 基于 RedissonClient 的分布式验证码存储配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean(CaptchaStore.class)
    static class RedissonCaptchaConfiguration {

        @Bean
        public CaptchaStore redissonCaptchaStore(RedissonClient redissonClient) {
            return new RedissonCaptchaStore(redissonClient);
        }
    }

    /**
     * Tier 2: 基于 StringRedisTemplate 的分布式验证码存储配置
     * 仅在未命中 Redisson 且未自定义 CaptchaStore 时生效
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(value = CaptchaStore.class, type = "org.redisson.api.RedissonClient")
    static class RedisCaptchaConfiguration {

        @Bean
        public CaptchaStore redisCaptchaStore(StringRedisTemplate redisTemplate) {
            return new RedisCaptchaStore(redisTemplate);
        }
    }

    /**
     * Tier 3: 纯 JDK 单机内存验证码存储兜底配置
     * 仅在未命中 Redisson、未命中 Redis 且未自定义 CaptchaStore 时生效
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(value = CaptchaStore.class, type = {
            "org.redisson.api.RedissonClient",
            "org.springframework.data.redis.core.StringRedisTemplate"
    })
    static class InMemoryCaptchaConfiguration {

        @Bean
        public CaptchaStore inMemoryCaptchaStore(CaptchaProperties properties,
                                                 @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
            CaptchaProperties.InMemory inMem = properties.getInMemory() != null
                    ? properties.getInMemory()
                    : new CaptchaProperties.InMemory();
            return new InMemoryCaptchaStore(
                    inMem.getMaxCapacity(),
                    inMem.getCleanInterval(),
                    taskExecutorProvider.getIfAvailable()
            );
        }
    }

    /**
     * 装配默认纯矢量 SVG 图形验证码渲染器（零系统字体依赖）
     *
     * @return {@link SvgCaptchaRenderer} 实例
     */
    @Bean
    @ConditionalOnMissingBean(CaptchaRenderer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.web.captcha", name = "renderer", havingValue = "SVG", matchIfMissing = true)
    public CaptchaRenderer svgCaptchaRenderer() {
        return new SvgCaptchaRenderer();
    }

    /**
     * 装配基于 JDK BufferedImage 的 PNG 图形验证码渲染器
     *
     * @return {@link PngCaptchaRenderer} 实例
     */
    @Bean
    @ConditionalOnMissingBean(CaptchaRenderer.class)
    @ConditionalOnProperty(prefix = "ateng.boot.web.captcha", name = "renderer", havingValue = "PNG")
    public CaptchaRenderer pngCaptchaRenderer() {
        return new PngCaptchaRenderer();
    }

    /**
     * 装配图形验证码门面 {@link CaptchaTemplate}
     *
     * @param captchaStore    自适应注入的验证码存储实例
     * @param captchaRenderer 图像渲染器实例
     * @param properties      图形验证码配置属性
     * @return {@link CaptchaTemplate} 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CaptchaTemplate captchaTemplate(CaptchaStore captchaStore,
                                           CaptchaRenderer captchaRenderer,
                                           CaptchaProperties properties,
                                           @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
        return new CaptchaTemplate(captchaStore, captchaRenderer, properties, taskExecutorProvider.getIfAvailable());
    }
}
