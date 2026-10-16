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
    @DisplayName("当配置 ateng.boot.web.cors.enabled=true 时自动装配 CorsFilter")
    void shouldEnableCorsFilterWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.web.cors.enabled=true")
                .run(context -> {
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
}
