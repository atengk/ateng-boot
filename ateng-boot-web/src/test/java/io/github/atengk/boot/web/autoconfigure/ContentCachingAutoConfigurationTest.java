package io.github.atengk.boot.web.autoconfigure;

import io.github.atengk.boot.web.filter.ContentCachingFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体可重读管道条件装配测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("请求体可重读管道条件装配测试")
class ContentCachingAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengWebAutoConfiguration.class));

    @Test
    @DisplayName("默认情况下未开启加签与 Payload 采样时不装配 ContentCachingFilter")
    void shouldNotLoadWhenDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("contentCachingFilterRegistration");
            assertThat(context).doesNotHaveBean(ContentCachingFilter.class);
        });
    }

    @Test
    @DisplayName("开启 ateng.boot.web.sign.enabled=true 时自动装配 ContentCachingFilter")
    void shouldLoadWhenSignEnabled() {
        contextRunner.withPropertyValues("ateng.boot.web.sign.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("contentCachingFilterRegistration");
                    FilterRegistrationBean<?> registration = context.getBean("contentCachingFilterRegistration", FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(ContentCachingFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(ContentCachingFilter.ORDER);
                });
    }

    @Test
    @DisplayName("开启 ateng.boot.web.payload-log.enabled=true 时自动装配 ContentCachingFilter")
    void shouldLoadWhenPayloadLogEnabled() {
        contextRunner.withPropertyValues("ateng.boot.web.payload-log.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("contentCachingFilterRegistration");
                    FilterRegistrationBean<?> registration = context.getBean("contentCachingFilterRegistration", FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(ContentCachingFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(ContentCachingFilter.ORDER);
                });
    }

    @Test
    @DisplayName("开启 ateng.boot.web.crypto.enabled=true 时自动装配 ContentCachingFilter 且不缩减更大的 body-cache 上限")
    void shouldLoadWhenCryptoEnabledWithoutShrinkingBodyCache() {
        contextRunner.withPropertyValues("ateng.boot.web.crypto.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("contentCachingFilterRegistration");
                    FilterRegistrationBean<?> registration = context.getBean("contentCachingFilterRegistration", FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(ContentCachingFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(ContentCachingFilter.ORDER);
                });

        // sign.enabled=false 且 body-cache=10MB > crypto=2MB 时保留 10MB，不意外缩减
        contextRunner.withPropertyValues(
                "ateng.boot.web.crypto.enabled=true",
                "ateng.boot.web.body-cache.max-body-size=10MB",
                "ateng.boot.web.crypto.max-body-size=2MB"
        ).run(context -> {
            FilterRegistrationBean<?> registration = context.getBean("contentCachingFilterRegistration", FilterRegistrationBean.class);
            assertThat(registration.getFilter()).extracting("maxBodySize").isEqualTo(10 * 1024 * 1024);
        });

        // body-cache=2MB < crypto=5MB 时自动扩容至 5MB
        contextRunner.withPropertyValues(
                "ateng.boot.web.crypto.enabled=true",
                "ateng.boot.web.body-cache.max-body-size=2MB",
                "ateng.boot.web.crypto.max-body-size=5MB"
        ).run(context -> {
            FilterRegistrationBean<?> registration = context.getBean("contentCachingFilterRegistration", FilterRegistrationBean.class);
            assertThat(registration.getFilter()).extracting("maxBodySize").isEqualTo(5 * 1024 * 1024);
        });
    }

    @Test
    @DisplayName("显式配置 sign.enabled=false 且 payload-log.enabled=false 时不装配")
    void shouldNotLoadWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues(
                "ateng.boot.web.sign.enabled=false",
                "ateng.boot.web.payload-log.enabled=false"
        ).run(context -> {
            assertThat(context).doesNotHaveBean("contentCachingFilterRegistration");
        });
    }

    @Test
    @DisplayName("当 ateng.boot.web.enabled=false 时整个 Web 增强均不装配")
    void shouldNotLoadWhenWebDisabled() {
        contextRunner.withPropertyValues(
                "ateng.boot.web.enabled=false",
                "ateng.boot.web.sign.enabled=true"
        ).run(context -> {
            assertThat(context).doesNotHaveBean("contentCachingFilterRegistration");
        });
    }

    @Test
    @DisplayName("支持业务自定义 contentCachingFilterRegistration 时平滑退让")
    void shouldBackOffWhenCustomBeanProvided() {
        FilterRegistrationBean<ContentCachingFilter> customRegistration =
                new FilterRegistrationBean<>(new ContentCachingFilter(1024));
        customRegistration.setName("contentCachingFilterRegistration");

        contextRunner.withPropertyValues("ateng.boot.web.sign.enabled=true")
                .withBean("contentCachingFilterRegistration", FilterRegistrationBean.class, () -> customRegistration)
                .run(context -> {
                    assertThat(context).hasBean("contentCachingFilterRegistration");
                    assertThat(context.getBean("contentCachingFilterRegistration")).isSameAs(customRegistration);
                });
    }
}
