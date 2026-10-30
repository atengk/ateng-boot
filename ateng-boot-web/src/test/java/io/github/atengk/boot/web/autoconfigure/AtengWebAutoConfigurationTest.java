package io.github.atengk.boot.web.autoconfigure;

import io.github.atengk.boot.web.handler.GlobalExceptionHandler;
import io.github.atengk.boot.web.jackson.JacksonCustomizer;
import io.github.atengk.boot.web.trace.TraceFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web 模块自动化装配测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("Web 自动化装配测试")
class AtengWebAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengWebAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功装配 TraceFilter 注册器、GlobalExceptionHandler 与 JacksonCustomizer，不装配 CorsFilter")
    void shouldAutoConfigureDefaultComponents() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("traceFilterRegistration");
            org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration =
                    context.getBean("traceFilterRegistration", org.springframework.boot.web.servlet.FilterRegistrationBean.class);
            assertThat(registration.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context).hasSingleBean(JacksonCustomizer.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.web.ready.ProductionSanityChecker.class);
            assertThat(context).hasSingleBean(io.github.atengk.boot.web.ready.ApplicationReadyCardListener.class);
            assertThat(context).doesNotHaveBean(CorsFilter.class);
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.web.aspect.WebLogAspect.class);
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.trace.enabled=false 时禁用 TraceFilter 注册器")
    void shouldDisableTraceFilterWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.web.trace.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("traceFilterRegistration");
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.cors.enabled=true 时自动装配 CorsOriginProvider, CorsConfigurationSource 与 CorsFilter")
    void shouldEnableCorsFilterWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.web.cors.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.web.cors.CorsOriginProvider.class);
                    assertThat(context).hasSingleBean(org.springframework.web.cors.CorsConfigurationSource.class);
                    assertThat(context).hasSingleBean(CorsFilter.class);
                });
    }

    @Test
    @DisplayName("当容器提供自定义 CorsOriginProvider 时，默认提供者自动退让")
    void shouldAllowCustomCorsOriginProviderOverride() {
        io.github.atengk.boot.web.cors.CorsOriginProvider customProvider = origin -> "https://custom.com".equals(origin);
        contextRunner.withPropertyValues("ateng.boot.web.cors.enabled=true")
                .withBean(io.github.atengk.boot.web.cors.CorsOriginProvider.class, () -> customProvider)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.web.cors.CorsOriginProvider.class);
                    assertThat(context.getBean(io.github.atengk.boot.web.cors.CorsOriginProvider.class)).isSameAs(customProvider);
                    assertThat(context).hasSingleBean(CorsFilter.class);
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.log.enabled=true 时自动装配 WebLogAspect 切面")
    void shouldEnableWebLogAspectWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.web.log.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.web.aspect.WebLogAspect.class);
                });
    }

    @Test
    @DisplayName("默认配置下成功装配 SlowRequestFilter 注册器且优先级正确")
    void shouldAutoConfigureSlowRequestFilterByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("slowRequestFilterRegistration");
            org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration =
                    context.getBean("slowRequestFilterRegistration", org.springframework.boot.web.servlet.FilterRegistrationBean.class);
            assertThat(registration.getOrder()).isEqualTo(io.github.atengk.boot.web.filter.SlowRequestFilter.ORDER);
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.slow-request.enabled=false 时禁用 SlowRequestFilter 注册器")
    void shouldDisableSlowRequestFilterWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.web.slow-request.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("slowRequestFilterRegistration");
                });
    }

    @Test
    @DisplayName("当容器声明自定义 SlowRequestFilter Bean 时自动绑定至 FilterRegistrationBean")
    void shouldRespectCustomSlowRequestFilter() {
        io.github.atengk.boot.web.filter.SlowRequestFilter customFilter =
                new io.github.atengk.boot.web.filter.SlowRequestFilter(java.time.Duration.ofSeconds(10));
        contextRunner.withBean(io.github.atengk.boot.web.filter.SlowRequestFilter.class, () -> customFilter)
                .run(context -> {
                    assertThat(context).hasBean("slowRequestFilterRegistration");
                    org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration =
                            context.getBean("slowRequestFilterRegistration", org.springframework.boot.web.servlet.FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isSameAs(customFilter);
                });
    }

    @Test
    @DisplayName("默认配置下不装配 PayloadLoggingFilter 注册器")
    void shouldNotAutoConfigurePayloadLoggingFilterByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("payloadLoggingFilterRegistration");
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.payload-log.enabled=true 时自动装配 PayloadLoggingFilter 与 ContentCachingFilter")
    void shouldEnablePayloadLoggingFilterWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.web.payload-log.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("payloadLoggingFilterRegistration");
                    assertThat(context).hasBean("contentCachingFilterRegistration");
                    org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration =
                            context.getBean("payloadLoggingFilterRegistration", org.springframework.boot.web.servlet.FilterRegistrationBean.class);
                    assertThat(registration.getOrder()).isEqualTo(io.github.atengk.boot.web.filter.PayloadLoggingFilter.ORDER);
                });
    }

    @Test
    @DisplayName("当容器声明自定义 PayloadLoggingFilter Bean 时自动绑定至 FilterRegistrationBean")
    void shouldRespectCustomPayloadLoggingFilter() {
        io.github.atengk.boot.web.filter.PayloadLoggingFilter customFilter =
                new io.github.atengk.boot.web.filter.PayloadLoggingFilter(org.springframework.util.unit.DataSize.ofKilobytes(16));
        contextRunner.withPropertyValues("ateng.boot.web.payload-log.enabled=true")
                .withBean(io.github.atengk.boot.web.filter.PayloadLoggingFilter.class, () -> customFilter)
                .run(context -> {
                    assertThat(context).hasBean("payloadLoggingFilterRegistration");
                    org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration =
                            context.getBean("payloadLoggingFilterRegistration", org.springframework.boot.web.servlet.FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isSameAs(customFilter);
                });
    }

    @Test
    @DisplayName("默认配置下自动装配 SecurityHeadersFilter 注册器")
    void shouldAutoConfigureSecurityHeadersFilterByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("securityHeadersFilterRegistration");
            org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration =
                    context.getBean("securityHeadersFilterRegistration", org.springframework.boot.web.servlet.FilterRegistrationBean.class);
            assertThat(registration.getOrder()).isEqualTo(io.github.atengk.boot.web.filter.SecurityHeadersFilter.ORDER);
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.security-headers.enabled=false 时不装配 SecurityHeadersFilter")
    void shouldDisableSecurityHeadersFilterWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.web.security-headers.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("securityHeadersFilterRegistration");
                });
    }

    @Test
    @DisplayName("默认配置下不装配 ShallowEtagHeaderFilter")
    void shouldNotAutoConfigureEtagFilterByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("shallowEtagHeaderFilterRegistration");
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.etag.enabled=true 时自动装配 ShallowEtagHeaderFilter")
    void shouldEnableEtagFilterWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.web.etag.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("shallowEtagHeaderFilterRegistration");
                });
    }

    @Test
    @DisplayName("默认配置下不装配 XSS 清洗器与过滤器")
    void shouldNotAutoConfigureXssByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("xssFilterRegistration");
            assertThat(context).doesNotHaveBean("xssCleaner");
            assertThat(context).doesNotHaveBean("xssJacksonCustomizer");
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.xss.enabled=true 时自动装配 XssFilter、XssCleaner 与 xssJacksonCustomizer")
    void shouldAutoConfigureXssWhenEnabled() {
        contextRunner.withPropertyValues("ateng.boot.web.xss.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("xssFilterRegistration");
                    assertThat(context).hasBean("xssCleaner");
                    assertThat(context).hasBean("xssJacksonCustomizer");
                    org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration =
                            context.getBean("xssFilterRegistration", org.springframework.boot.web.servlet.FilterRegistrationBean.class);
                    assertThat(registration.getOrder()).isEqualTo(io.github.atengk.boot.web.xss.XssFilter.ORDER);
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.web.ready-card.enabled=false 与 sanity-check.enabled=false 时禁用就绪卡片与防呆检查")
    void shouldDisableReadyCardAndSanityCheckerWhenConfigured() {
        contextRunner.withPropertyValues(
                "ateng.boot.web.ready-card.enabled=false",
                "ateng.boot.web.sanity-check.enabled=false"
        ).run(context -> {
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.web.ready.ProductionSanityChecker.class);
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.web.ready.ApplicationReadyCardListener.class);
        });
    }
}

