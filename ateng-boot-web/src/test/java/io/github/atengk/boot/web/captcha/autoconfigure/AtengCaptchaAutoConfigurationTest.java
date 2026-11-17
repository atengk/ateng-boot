package io.github.atengk.boot.web.captcha.autoconfigure;

import io.github.atengk.boot.web.captcha.model.CaptchaResult;
import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;
import io.github.atengk.boot.web.captcha.renderer.CaptchaRenderer;
import io.github.atengk.boot.web.captcha.renderer.PngCaptchaRenderer;
import io.github.atengk.boot.web.captcha.renderer.SvgCaptchaRenderer;
import io.github.atengk.boot.web.captcha.store.CaptchaStore;
import io.github.atengk.boot.web.captcha.store.InMemoryCaptchaStore;
import io.github.atengk.boot.web.captcha.store.RedisCaptchaStore;
import io.github.atengk.boot.web.captcha.store.RedissonCaptchaStore;
import io.github.atengk.boot.web.captcha.template.CaptchaTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图形验证码三级自适应自动装配单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@DisplayName("AtengCaptchaAutoConfiguration 自动装配测试")
class AtengCaptchaAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengCaptchaAutoConfiguration.class));

    @Test
    @DisplayName("默认无分布式中间件时装配 InMemoryCaptchaStore、SvgCaptchaRenderer 与 CaptchaTemplate")
    void shouldAutoConfigureDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CaptchaStore.class);
            assertThat(context).hasSingleBean(InMemoryCaptchaStore.class);
            assertThat(context).doesNotHaveBean(RedissonCaptchaStore.class);
            assertThat(context).doesNotHaveBean(RedisCaptchaStore.class);
            assertThat(context).hasSingleBean(CaptchaRenderer.class);
            assertThat(context).hasSingleBean(SvgCaptchaRenderer.class);
            assertThat(context).hasSingleBean(CaptchaTemplate.class);

            CaptchaResult generated = context.getBean(CaptchaTemplate.class).generate();
            assertThat(generated.imageBase64()).startsWith("data:image/svg+xml;base64,");
        });
    }

    @Test
    @DisplayName("配置 renderer=PNG 时切换为 PngCaptchaRenderer")
    void shouldSwitchToPngRendererWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.web.captcha.renderer=PNG")
                .run(context -> {
                    assertThat(context).hasSingleBean(CaptchaRenderer.class);
                    assertThat(context).hasSingleBean(PngCaptchaRenderer.class);
                    assertThat(context).doesNotHaveBean(SvgCaptchaRenderer.class);

                    CaptchaResult generated = context.getBean(CaptchaTemplate.class).generate();
                    assertThat(generated.imageBase64()).startsWith("data:image/png;base64,");
                });
    }

    @Test
    @DisplayName("仅存在 StringRedisTemplate 时装配 Tier 2 RedisCaptchaStore")
    void shouldAutoConfigureRedisStoreWhenStringRedisTemplatePresent() {
        contextRunner.withUserConfiguration(RedisMockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CaptchaStore.class);
                    assertThat(context).hasSingleBean(RedisCaptchaStore.class);
                    assertThat(context).doesNotHaveBean(RedissonCaptchaStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryCaptchaStore.class);
                });
    }

    @Test
    @DisplayName("同时存在 RedissonClient 与 StringRedisTemplate 时由 Tier 1 RedissonCaptchaStore 胜出")
    void shouldPrioritizeRedissonStoreOverRedisStore() {
        contextRunner.withUserConfiguration(RedissonMockConfig.class, RedisMockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CaptchaStore.class);
                    assertThat(context).hasSingleBean(RedissonCaptchaStore.class);
                    assertThat(context).doesNotHaveBean(RedisCaptchaStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryCaptchaStore.class);
                });
    }

    @Test
    @DisplayName("用户自定义 CaptchaStore 或 CaptchaRenderer 时自动装配平滑退让")
    void shouldRespectCustomBeans() {
        contextRunner.withUserConfiguration(CustomBeansConfig.class, RedissonMockConfig.class, RedisMockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CaptchaStore.class);
                    assertThat(context.getBean(CaptchaStore.class)).isInstanceOf(CustomStore.class);
                    assertThat(context.getBean(CaptchaRenderer.class)).isInstanceOf(CustomRenderer.class);
                    assertThat(context).doesNotHaveBean(RedissonCaptchaStore.class);
                    assertThat(context).doesNotHaveBean(SvgCaptchaRenderer.class);
                });
    }

    @Test
    @DisplayName("配置 enabled=false 时彻底关闭图形验证码自动装配")
    void shouldBackOffWhenDisabled() {
        contextRunner.withPropertyValues("ateng.boot.web.captcha.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CaptchaStore.class);
                    assertThat(context).doesNotHaveBean(CaptchaRenderer.class);
                    assertThat(context).doesNotHaveBean(CaptchaTemplate.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class RedissonMockConfig {
        @Bean
        public RedissonClient redissonClient() {
            return Mockito.mock(RedissonClient.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisMockConfig {
        @Bean
        public StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBeansConfig {
        @Bean
        public CaptchaStore customCaptchaStore() {
            return new CustomStore();
        }

        @Bean
        public CaptchaRenderer customCaptchaRenderer() {
            return new CustomRenderer();
        }
    }

    static class CustomStore implements CaptchaStore {
        @Override
        public void save(String key, String answer, Duration ttl) {
        }

        @Override
        public String getAndDelete(String key) {
            return null;
        }

        @Override
        public void delete(String key) {
        }
    }

    static class CustomRenderer implements CaptchaRenderer {
        @Override
        public String render(String displayText, CaptchaProperties properties) {
            return "data:image/svg+xml;base64,custom";
        }
    }
}
