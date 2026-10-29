package io.github.atengk.boot.http.autoconfigure;

import io.github.atengk.boot.http.error.HttpErrorHandler;
import io.github.atengk.boot.http.factory.RestClientRegistry;
import io.github.atengk.boot.http.interceptor.HttpLoggingInterceptor;
import io.github.atengk.boot.http.interceptor.HttpRetryInterceptor;
import io.github.atengk.boot.http.interceptor.TraceContextInterceptor;
import io.github.atengk.boot.http.template.HttpTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 自动装配自动化测试
 * 验证核心组件装配、条件化开闭、RestClientBuilderConfigurer 官方基线融合与 @ConditionalOnMissingBean 覆盖接管
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("AtengHttpAutoConfiguration 自动装配测试")
class AtengHttpAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengHttpAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功装配全部 HTTP 核心基础设施")
    void shouldAutoConfigureDefaultComponents() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ClientHttpRequestFactory.class);
            assertThat(context).hasSingleBean(HttpErrorHandler.class);
            assertThat(context).hasSingleBean(TraceContextInterceptor.class);
            assertThat(context).hasSingleBean(HttpRetryInterceptor.class);
            assertThat(context).hasSingleBean(HttpLoggingInterceptor.class);
            assertThat(context).hasSingleBean(RestClient.Builder.class);
            assertThat(context).hasSingleBean(HttpTemplate.class);
            assertThat(context).hasSingleBean(RestClientRegistry.class);
        });
    }

    @Test
    @DisplayName("禁用属性开关时，核心基础设施全量平滑不装配")
    void shouldNotAutoConfigureWhenDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.http.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClientHttpRequestFactory.class);
                    assertThat(context).doesNotHaveBean(HttpErrorHandler.class);
                    assertThat(context).doesNotHaveBean(HttpTemplate.class);
                    assertThat(context).doesNotHaveBean(RestClientRegistry.class);
                });
    }

    @Test
    @DisplayName("官方基线融合：存在 RestClientBuilderConfigurer 时优先调用其 configure 方法")
    void shouldInvokeRestClientBuilderConfigurerWhenAvailable() {
        AtomicBoolean configurerInvoked = new AtomicBoolean(false);

        contextRunner
                .withBean(RestClientBuilderConfigurer.class, () -> new RestClientBuilderConfigurer() {
                    @Override
                    public RestClient.Builder configure(RestClient.Builder builder) {
                        configurerInvoked.set(true);
                        return builder;
                    }
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(RestClient.Builder.class);
                    // 触发 Bean 初始化
                    context.getBean(RestClient.Builder.class);
                    assertThat(configurerInvoked.get()).isTrue();
                });
    }

    @Test
    @DisplayName("无 configurer 时兜底应用 RestClientCustomizer")
    void shouldApplyRestClientCustomizerWhenNoConfigurer() {
        AtomicBoolean customizerInvoked = new AtomicBoolean(false);

        contextRunner
                .withBean("testCustomizer", RestClientCustomizer.class, () -> builder -> customizerInvoked.set(true))
                .run(context -> {
                    assertThat(context).hasSingleBean(RestClient.Builder.class);
                    context.getBean(RestClient.Builder.class);
                    assertThat(customizerInvoked.get()).isTrue();
                });
    }

    @Test
    @DisplayName("用户自定义基础设施优先覆盖：遵循 @ConditionalOnMissingBean 铁律")
    void shouldRespectCustomUserBeans() {
        contextRunner
                .withUserConfiguration(CustomUserConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ClientHttpRequestFactory.class);
                    assertThat(context.getBean(ClientHttpRequestFactory.class))
                            .isInstanceOf(SimpleClientHttpRequestFactory.class);

                    assertThat(context).hasSingleBean(HttpErrorHandler.class);
                    assertThat(context.getBean(HttpErrorHandler.class))
                            .isInstanceOf(CustomHttpErrorHandler.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomUserConfiguration {

        @Bean
        public ClientHttpRequestFactory clientHttpRequestFactory() {
            return new SimpleClientHttpRequestFactory();
        }

        @Bean
        public HttpErrorHandler httpErrorHandler() {
            return new CustomHttpErrorHandler();
        }
    }

    static class CustomHttpErrorHandler extends HttpErrorHandler {
    }
}
