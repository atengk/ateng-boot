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
    @DisplayName("开启 ateng.boot.web.sign.enabled=true 时自动装配 ContentCachingFilter 并兼容旧别名")
    void shouldLoadWhenSignEnabled() {
        contextRunner.withPropertyValues("ateng.boot.web.sign.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("contentCachingFilterRegistration");
                    assertThat(context).hasBean("requestBodyCachingFilterRegistration");
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
