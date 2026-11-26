package io.github.atengk.boot.web.autoconfigure;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.aspect.OperateLogAspect;
import io.github.atengk.boot.web.aspect.WebLogAspect;
import io.github.atengk.boot.web.audit.DefaultOperateLogStorageProvider;
import io.github.atengk.boot.web.audit.OperateLogStorageProvider;
import io.github.atengk.boot.web.audit.OperateLogUrlCustomizer;
import io.github.atengk.boot.web.audit.OperateLogUrlStrategy;
import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化操作审计自动装配测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("结构化操作审计自动装配测试")
class OperateLogAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengWebAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("默认情况下自动装配 OperateLogAspect 与 DefaultOperateLogStorageProvider")
    void shouldLoadOperateLogBeansByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OperateLogAspect.class);
            assertThat(context).hasSingleBean(OperateLogStorageProvider.class);
            assertThat(context.getBean(OperateLogStorageProvider.class)).isInstanceOf(DefaultOperateLogStorageProvider.class);
        });
    }

    @Test
    @DisplayName("业务自定义 OperateLogStorageProvider 时默认实现平滑退让")
    void shouldBackOffWhenCustomStorageProviderProvided() {
        OperateLogStorageProvider customProvider = (OperateLogModel model) -> {
            // custom persistence
        };

        contextRunner.withBean(OperateLogStorageProvider.class, () -> customProvider)
                .run(context -> {
                    assertThat(context).hasSingleBean(OperateLogStorageProvider.class);
                    assertThat(context.getBean(OperateLogStorageProvider.class)).isSameAs(customProvider);
                    assertThat(context).hasSingleBean(OperateLogAspect.class);
                });
    }

    @Test
    @DisplayName("配置 ateng.boot.web.operate-log.enabled=false 时切面不装配")
    void shouldNotLoadAspectWhenDisabled() {
        contextRunner.withPropertyValues("ateng.boot.web.operate-log.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OperateLogAspect.class);
                });
    }

    @Test
    @DisplayName("默认加载 11 个敏感排除词基线，且支持通过配置文件覆盖 operate-log/log default-exclude-params")
    void shouldBindDefaultAndCustomExcludeParamsProperties() {
        contextRunner.withPropertyValues("ateng.boot.web.log.enabled=true")
                .run(context -> {
                    WebProperties properties = context.getBean(WebProperties.class);
                    assertThat(properties.getOperateLog().getDefaultExcludeParams())
                            .containsExactly("password", "passwd", "pwd", "oldPassword", "newPassword",
                                    "confirmPassword", "secret", "token", "accessToken", "refreshToken", "authorization");
                    assertThat(properties.getLog().getDefaultExcludeParams())
                            .containsExactlyElementsOf(properties.getOperateLog().getDefaultExcludeParams());
                    assertThat(context).hasSingleBean(WebLogAspect.class);

                    OperateLogStorageProvider provider = context.getBean(OperateLogStorageProvider.class);
                    OperateLogModel model = new OperateLogModel();
                    model.setModule("autowire-check");
                    provider.store(model);
                });

        contextRunner.withPropertyValues(
                        "ateng.boot.web.log.enabled=true",
                        "ateng.boot.web.operate-log.default-exclude-params[0]=customPin",
                        "ateng.boot.web.log.default-exclude-params[0]=customSecret"
                )
                .run(context -> {
                    WebProperties properties = context.getBean(WebProperties.class);
                    assertThat(properties.getOperateLog().getDefaultExcludeParams()).containsExactly("customPin");
                    assertThat(properties.getLog().getDefaultExcludeParams()).containsExactly("customSecret");
                });
    }

    @Test
    @DisplayName("默认开启 User-Agent 同步采集与解析，支持通过配置覆盖开关与最大长度")
    void shouldBindUserAgentProperties() {
        contextRunner.run(context -> {
            WebProperties properties = context.getBean(WebProperties.class);
            assertThat(properties.getOperateLog().isCollectUserAgent()).isTrue();
            assertThat(properties.getOperateLog().isParseUserAgent()).isTrue();
            assertThat(properties.getOperateLog().getUserAgentMaxLength()).isEqualTo(512);
        });

        contextRunner.withPropertyValues(
                        "ateng.boot.web.operate-log.collect-user-agent=false",
                        "ateng.boot.web.operate-log.parse-user-agent=false",
                        "ateng.boot.web.operate-log.user-agent-max-length=128"
                )
                .run(context -> {
                    WebProperties properties = context.getBean(WebProperties.class);
                    assertThat(properties.getOperateLog().isCollectUserAgent()).isFalse();
                    assertThat(properties.getOperateLog().isParseUserAgent()).isFalse();
                    assertThat(properties.getOperateLog().getUserAgentMaxLength()).isEqualTo(128);
                });
    }

    @Test
    @DisplayName("默认 URL 采集策略为 RAW_URI，支持通过配置文件覆盖 url-strategy 与 template-urls")
    void shouldBindOperateLogUrlStrategyProperties() {
        contextRunner.run(context -> {
            WebProperties properties = context.getBean(WebProperties.class);
            assertThat(properties.getOperateLog().getUrlStrategy()).isEqualTo(OperateLogUrlStrategy.RAW_URI);
            assertThat(properties.getOperateLog().getTemplateUrls()).isEmpty();
        });

        contextRunner.withPropertyValues(
                        "ateng.boot.web.operate-log.url-strategy=BEST_MATCHING_PATTERN",
                        "ateng.boot.web.operate-log.template-urls[0]=/admin/auth/online/**",
                        "ateng.boot.web.operate-log.template-urls[1]=/api/sessions/**"
                )
                .run(context -> {
                    WebProperties properties = context.getBean(WebProperties.class);
                    assertThat(properties.getOperateLog().getUrlStrategy()).isEqualTo(OperateLogUrlStrategy.BEST_MATCHING_PATTERN);
                    assertThat(properties.getOperateLog().getTemplateUrls())
                            .containsExactly("/admin/auth/online/**", "/api/sessions/**");
                });
    }

    @Test
    @DisplayName("自动装配容器中的多个 @Order OperateLogUrlCustomizer Bean 并按序链式执行")
    void shouldAutoWireOrderedOperateLogUrlCustomizers() {
        AtomicReference<OperateLogModel> captured = new AtomicReference<>();
        contextRunner
                .withBean(OperateLogStorageProvider.class, () -> captured::set)
                .withUserConfiguration(OrderedUrlCustomizerConfiguration.class)
                .run(context -> {
                    OperateLogAspect aspect = context.getBean(OperateLogAspect.class);
                    AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new SampleEndpoint());
                    proxyFactory.setProxyTargetClass(true);
                    proxyFactory.addAspect(aspect);
                    SampleEndpoint proxy = proxyFactory.getProxy();

                    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/sessions/secret-xyz");
                    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

                    proxy.revoke();
                    assertThat(captured.get()).isNotNull();
                    assertThat(captured.get().getRequestUrl()).isEqualTo("/api/sessions/secret-xyz|first|second");
                });
    }

    static class SampleEndpoint {
        @OperateLog(module = "会话", type = OperateTypeEnum.DELETE)
        public Result<Void> revoke() {
            return Result.success();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderedUrlCustomizerConfiguration {

        @Bean
        @Order(20)
        OperateLogUrlCustomizer secondCustomizer() {
            return ctx -> ctx.resolvedUrl() + "|second";
        }

        @Bean
        @Order(10)
        OperateLogUrlCustomizer firstCustomizer() {
            return ctx -> ctx.resolvedUrl() + "|first";
        }
    }
}
