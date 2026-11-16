package io.github.atengk.boot.mail.autoconfigure;

import io.github.atengk.boot.mail.event.MailAccountEvictEvent;
import io.github.atengk.boot.mail.handler.MailSendFailureHandler;
import io.github.atengk.boot.mail.interceptor.MailSandboxInterceptor;
import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
import io.github.atengk.boot.mail.listener.MailAccountEvictListener;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.model.MailMessage;
import io.github.atengk.boot.mail.properties.MailProperties;
import io.github.atengk.boot.mail.provider.MailAccountProvider;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.mail.resolver.MailAccountResolver;
import io.github.atengk.boot.mail.resolver.TenantMailAccountResolver;
import io.github.atengk.boot.mail.template.MailTemplate;
import io.github.atengk.boot.mail.template.renderer.MailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.SimpleMailTemplateRenderer;
import io.github.atengk.boot.mail.template.renderer.ThymeleafMailTemplateRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件自动装配条件与平滑探测测试用例
 *
 * @author Ateng
 * @since 2026-10-22
 */
class AtengMailAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengMailAutoConfiguration.class));

    @Test
    @DisplayName("缺外部配置时静默跳过自动装配并不引发崩溃")
    void testAutoConfigurationSkippedWhenNoProperties() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(MailTemplate.class);
            assertThat(context).doesNotHaveBean(MailAccountRegistry.class);
        });
    }

    @Test
    @DisplayName("显式配置顶级单账号时成功装配组件")
    void testAutoConfigurationWithTopLevelAccount() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.host=smtp.example.com",
                        "ateng.boot.mail.username=user@example.com",
                        "ateng.boot.mail.password=secret"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    assertThat(context).hasSingleBean(MailAccountRegistry.class);
                    assertThat(context).hasSingleBean(MailAccountEvictListener.class);
                    assertThat(context).hasSingleBean(MailAccountResolver.class);
                    assertThat(context.getBean(MailAccountResolver.class)).isInstanceOf(TenantMailAccountResolver.class);
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    assertThat(registry.size()).isEqualTo(1);
                    assertThat(registry.getDefault().getHost()).isEqualTo("smtp.example.com");
                });
    }

    @Test
    @DisplayName("显式配置多命名账号字典时成功装配各账号")
    void testAutoConfigurationWithMultipleAccounts() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.accounts.ops.host=smtp.ops.com",
                        "ateng.boot.mail.accounts.ops.username=ops@ops.com",
                        "ateng.boot.mail.accounts.ops.password=pwd1",
                        "ateng.boot.mail.accounts.marketing.host=smtp.mkt.com",
                        "ateng.boot.mail.accounts.marketing.username=mkt@mkt.com",
                        "ateng.boot.mail.accounts.marketing.password=pwd2"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    assertThat(context).hasSingleBean(MailAccountRegistry.class);
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    assertThat(registry.size()).isEqualTo(2);
                    assertThat(registry.contains("ops")).isTrue();
                    assertThat(registry.contains("marketing")).isTrue();
                });
    }

    @Test
    @DisplayName("仅配置 Spring 官方原生配置时平滑纳管保底并完整透传扩展属性")
    void testAutoConfigurationWithSpringMailFallback() {
        contextRunner
                .withPropertyValues(
                        "spring.mail.host=smtp.springfallback.com",
                        "spring.mail.username=spring@test.com",
                        "spring.mail.password=springpwd",
                        "spring.mail.port=587",
                        "spring.mail.properties.mail.smtp.auth=true",
                        "spring.mail.properties.mail.custom.param=ateng-value"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    assertThat(registry.size()).isEqualTo(1);
                    assertThat(registry.getDefault().getHost()).isEqualTo("smtp.springfallback.com");
                    assertThat(registry.getDefault().getPort()).isEqualTo(587);
                    assertThat(registry.getDefault().getProperties())
                            .containsEntry("mail.smtp.auth", "true")
                            .containsEntry("mail.custom.param", "ateng-value");
                });
    }

    @Test
    @DisplayName("全局开关 enabled 为 false 时禁用装配")
    void testAutoConfigurationDisabled() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.enabled=false",
                        "ateng.boot.mail.host=smtp.example.com"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MailTemplate.class);
                    assertThat(context).doesNotHaveBean(MailAccountRegistry.class);
                });
    }

    @Test
    @DisplayName("默认情况下自动装配 SimpleMailTemplateRenderer")
    void testDefaultTemplateRenderer() {
        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplateRenderer.class);
                    assertThat(context.getBean(MailTemplateRenderer.class))
                            .isInstanceOf(SimpleMailTemplateRenderer.class);
                });
    }

    @Test
    @DisplayName("当容器中存在 SpringTemplateEngine 时自动装配 ThymeleafMailTemplateRenderer")
    void testThymeleafTemplateRendererAutoConfigured() {
        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .withBean(SpringTemplateEngine.class, SpringTemplateEngine::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplateRenderer.class);
                    assertThat(context.getBean(MailTemplateRenderer.class))
                            .isInstanceOf(ThymeleafMailTemplateRenderer.class);
                });
    }

    @Test
    @DisplayName("用户自定义声明 MailAccountResolver 时平滑覆盖默认解析器")
    void testCustomMailAccountResolverOverride() {
        MailAccountResolver customResolver = msg -> "custom-account";
        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .withBean(MailAccountResolver.class, () -> customResolver)
                .run(context -> {
                    assertThat(context).hasSingleBean(MailAccountResolver.class);
                    assertThat(context.getBean(MailAccountResolver.class)).isSameAs(customResolver);
                    MailTemplate template = context.getBean(MailTemplate.class);
                    assertThat(template.getAccountResolver()).isSameAs(customResolver);
                });
    }

    @Test
    @DisplayName("默认配置下成功装配专职 mailTaskExecutor 且注入 MailTemplate")
    void testMailTemplateInjectsDedicatedMailTaskExecutor() {
        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .run(context -> {
                    assertThat(context).hasBean("mailTaskExecutor");
                    Executor mailExecutor = context.getBean("mailTaskExecutor", Executor.class);
                    assertThat(mailExecutor).isNotNull();
                    MailTemplate template = context.getBean(MailTemplate.class);
                    assertThat(template.getExecutor()).isSameAs(mailExecutor);
                    if (mailExecutor instanceof ThreadPoolTaskExecutor pool) {
                        assertThat(pool.getThreadNamePrefix()).isEqualTo("mail-async-");
                    }
                });
    }

    @Test
    @DisplayName("显式配置 ateng.boot.mail.async.* 时专职线程池属性准确生效")
    void testMailTaskExecutorCustomProperties() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.host=smtp.example.com",
                        "ateng.boot.mail.async.core-pool-size=4",
                        "ateng.boot.mail.async.max-pool-size=12",
                        "ateng.boot.mail.async.queue-capacity=512",
                        "ateng.boot.mail.async.thread-name-prefix=test-mail-pool-"
                )
                .run(context -> {
                    assertThat(context).hasBean("mailTaskExecutor");
                    Executor mailExecutor = context.getBean("mailTaskExecutor", Executor.class);
                    assertThat(mailExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
                    ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) mailExecutor;
                    assertThat(pool.getCorePoolSize()).isEqualTo(4);
                    assertThat(pool.getMaxPoolSize()).isEqualTo(12);
                    assertThat(pool.getQueueCapacity()).isEqualTo(512);
                    assertThat(pool.getThreadNamePrefix()).isEqualTo("test-mail-pool-");
                });
    }

    @Test
    @DisplayName("禁用专职异步线程池时平滑回退使用全局 taskExecutor")
    void testFallbackToGlobalTaskExecutorWhenMailAsyncDisabled() {
        ThreadPoolTaskExecutor globalExecutor = new ThreadPoolTaskExecutor();
        globalExecutor.setThreadNamePrefix("global-task-");
        globalExecutor.initialize();

        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.host=smtp.example.com",
                        "ateng.boot.mail.async.enabled=false"
                )
                .withBean("taskExecutor", ThreadPoolTaskExecutor.class, () -> globalExecutor)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("mailTaskExecutor");
                    MailTemplate template = context.getBean(MailTemplate.class);
                    assertThat(template.getExecutor()).isSameAs(globalExecutor);
                });
    }

    @Test
    @DisplayName("用户自定义声明同名 mailTaskExecutor 时平滑覆盖默认执行器")
    void testCustomMailTaskExecutorOverride() {
        ThreadPoolTaskExecutor userExecutor = new ThreadPoolTaskExecutor();
        userExecutor.setThreadNamePrefix("user-defined-mail-");
        userExecutor.initialize();

        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .withBean("mailTaskExecutor", ThreadPoolTaskExecutor.class, () -> userExecutor)
                .run(context -> {
                    assertThat(context).hasBean("mailTaskExecutor");
                    MailTemplate template = context.getBean(MailTemplate.class);
                    assertThat(template.getExecutor()).isSameAs(userExecutor);
                });
    }

    @Test
    @DisplayName("在开启虚拟线程模式下自动装配 SimpleAsyncTaskExecutor 且不为 null")
    void testMailTaskExecutorVirtualThreadsMode() {
        if (Runtime.version().feature() >= 21) {
            contextRunner
                    .withPropertyValues(
                            "ateng.boot.mail.host=smtp.example.com",
                            "ateng.boot.mail.async.mode=VIRTUAL",
                            "ateng.boot.mail.async.virtual-thread-name-prefix=test-vt-mail-"
                    )
                    .run(context -> {
                        assertThat(context).hasBean("mailTaskExecutor");
                        Executor mailExecutor = context.getBean("mailTaskExecutor", Executor.class);
                        assertThat(mailExecutor).isInstanceOf(SimpleAsyncTaskExecutor.class);
                        SimpleAsyncTaskExecutor vtExecutor = (SimpleAsyncTaskExecutor) mailExecutor;
                        assertThat(vtExecutor.getThreadNamePrefix()).isEqualTo("test-vt-mail-");
                        MailTemplate template = context.getBean(MailTemplate.class);
                        assertThat(template.getExecutor()).isSameAs(vtExecutor);
                    });
        }
    }

    @Test
    @DisplayName("用户自定义声明 MailSendFailureHandler 时自动注入 MailTemplate")
    void testMailSendFailureHandlerInjected() {
        MailSendFailureHandler customHandler = (msg, cause) -> {};
        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .withBean("customFailureHandler", MailSendFailureHandler.class, () -> customHandler)
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    MailTemplate template = context.getBean(MailTemplate.class);
                    assertThat(template.getFailureHandlers()).contains(customHandler);
                });
    }

    @Test
    @DisplayName("用户自定义声明 MailSendInterceptor 时自动注入 MailTemplate")
    void testMailSendInterceptorInjected() {
        MailSendInterceptor customInterceptor = msg -> true;
        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .withBean("customInterceptor", MailSendInterceptor.class, () -> customInterceptor)
                .run(context -> {
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    MailTemplate template = context.getBean(MailTemplate.class);
                    assertThat(template.getInterceptors()).contains(customInterceptor);
                });
    }

    @Test
    @DisplayName("广播 MailAccountEvictEvent 领域事件通过 Spring 事件总线派发并精准注销动态账号")
    void testMailAccountEvictEventDispatchedViaSpringEventBus() {
        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .run(context -> {
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    MailProperties properties = context.getBean(MailProperties.class);

                    // 编程式注册动态账号
                    MailAccount dynamicAcc = MailAccount.builder()
                            .name("event-test-acc")
                            .host("smtp.dynamic.com")
                            .port(465)
                            .username("dynamic@dynamic.com")
                            .password("secret")
                            .build();
                    registry.getOrCreateDynamic("event-test-acc", dynamicAcc, properties);
                    assertThat(registry.getDynamic("event-test-acc")).isNotNull();

                    // 1. 发布指定账号的驱逐事件
                    context.publishEvent(new MailAccountEvictEvent(this, "event-test-acc"));
                    assertThat(registry.getDynamic("event-test-acc")).isNull();

                    // 2. 再次注册并测试全量驱逐事件
                    registry.getOrCreateDynamic("event-test-acc-2", dynamicAcc, properties);
                    assertThat(registry.dynamicSize()).isGreaterThan(0);
                    context.publishEvent(new MailAccountEvictEvent(this, null));
                    assertThat(registry.dynamicSize()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("开启 sandbox.enabled=true 时自动装配 MailSandboxInterceptor 并注入 MailTemplate")
    void testMailSandboxInterceptorAutoConfiguredWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.host=smtp.example.com",
                        "ateng.boot.mail.sandbox.enabled=true",
                        "ateng.boot.mail.sandbox.redirect-to=mail-trap@company.com"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MailSandboxInterceptor.class);
                    MailTemplate template = context.getBean(MailTemplate.class);
                    MailSandboxInterceptor interceptor = context.getBean(MailSandboxInterceptor.class);
                    assertThat(template.getInterceptors()).contains(interceptor);
                });
    }

    @Test
    @DisplayName("未开启 sandbox.enabled 时不装配 MailSandboxInterceptor")
    void testMailSandboxInterceptorNotConfiguredWhenDisabled() {
        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MailSandboxInterceptor.class);
                });
    }

    @Test
    @DisplayName("零静态配置但存在 MailAccountProvider Bean 时成功装配空注册表与 MailTemplate")
    void testAutoConfigurationWithMailAccountProviderOnly() {
        contextRunner
                .withUserConfiguration(DynamicProviderConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MailAccountRegistry.class);
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    assertThat(context.getBean(MailAccountRegistry.class).size()).isEqualTo(0);
                    MailTemplate mailTemplate = context.getBean(MailTemplate.class);
                    assertThat(mailTemplate.getAccountProvider()).isNotNull();
                });
    }

    @Test
    @DisplayName("零静态配置但开启 sandbox.enabled=true 且 log-only=true（含驼峰宽松绑定）时允许装配")
    void testAutoConfigurationWithSandboxLogOnlyWithoutAccounts() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.sandbox.enabled=true",
                        "ateng.boot.mail.sandbox.logOnly=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MailAccountRegistry.class);
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    assertThat(context).hasSingleBean(MailSandboxInterceptor.class);
                    assertThat(context.getBean(MailAccountRegistry.class).size()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("零静态配置但显式启用 allow-empty-accounts（含驼峰 allowEmptyAccounts）时允许装配")
    void testAutoConfigurationWithAllowEmptyAccounts() {
        contextRunner
                .withPropertyValues("ateng.boot.mail.allowEmptyAccounts=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(MailAccountRegistry.class);
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    assertThat(context.getBean(MailAccountRegistry.class).size()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("仅开启 sandbox.enabled=true 但未开启 log-only 且无账号时保持跳过装配")
    void testAutoConfigurationSkippedWhenSandboxWithoutLogOnlyAndNoAccounts() {
        contextRunner
                .withPropertyValues("ateng.boot.mail.sandbox.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MailAccountRegistry.class);
                    assertThat(context).doesNotHaveBean(MailTemplate.class);
                });
    }

    @Test
    @DisplayName("spring.mail.properties.* 遵循环境配置优先级，高优先级源不被低优先级源反向覆盖")
    void testSpringMailPropertiesHighestPrecedenceWins() {
        contextRunner
                .withInitializer(applicationContext -> {
                    var sources = applicationContext.getEnvironment().getPropertySources();
                    sources.addFirst(new MapPropertySource("lowPriorityMailProps", Map.of(
                            "spring.mail.host", "smtp.low.com",
                            "spring.mail.properties.mail.smtp.timeout", "9999",
                            "spring.mail.properties.mail.low.only", "low-val"
                    )));
                    sources.addFirst(new MapPropertySource("highPriorityMailProps", Map.of(
                            "spring.mail.host", "smtp.high.com",
                            "spring.mail.properties.mail.smtp.timeout", "1500"
                    )));
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(MailAccountRegistry.class);
                    MailAccountRegistry registry = context.getBean(MailAccountRegistry.class);
                    MailAccount defaultAcc = registry.getDefault();
                    assertThat(defaultAcc.getHost()).isEqualTo("smtp.high.com");
                    assertThat(defaultAcc.getProperties())
                            .containsEntry("mail.smtp.timeout", "1500")
                            .containsEntry("mail.low.only", "low-val");
                });
    }

    @Test
    @DisplayName("禁用 async.enabled=false 且无全局 taskExecutor 时不私自后台创建线程池")
    void testAsyncDisabledWithoutGlobalTaskExecutorDoesNotCreateBackgroundExecutor() {
        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.host=smtp.example.com",
                        "ateng.boot.mail.async.enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean("mailTaskExecutor");
                    assertThat(context).hasSingleBean(MailTemplate.class);
                    MailTemplate mailTemplate = context.getBean(MailTemplate.class);
                    assertThat(mailTemplate.getExecutor()).isNull();
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mailTemplate.sendAsync(new MailMessage()
                                    .to("disabled-async@example.com")
                                    .subject("s")
                                    .text("b")))
                            .isInstanceOf(io.github.atengk.boot.mail.exception.MailSendException.class);
                });
    }

    @Test
    @DisplayName("全量 @Validated 配置边界校检拦截越界并发度、超时与容量上限")
    void testPropertiesValidationRejectsOutOfBoundsValues() {
        ApplicationContextRunner validatedRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class, AtengMailAutoConfiguration.class));

        validatedRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com", "ateng.boot.mail.batch.concurrency=0")
                .run(context -> assertThat(context).hasFailed());

        validatedRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com", "ateng.boot.mail.batch.concurrency=33")
                .run(context -> assertThat(context).hasFailed());

        validatedRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com", "ateng.boot.mail.read-timeout=0s")
                .run(context -> assertThat(context).hasFailed());

        validatedRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com", "ateng.boot.mail.max-dynamic-accounts=0")
                .run(context -> assertThat(context).hasFailed());

        validatedRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com", "ateng.boot.mail.retry.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());

        validatedRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com", "ateng.boot.mail.retry.delay=-1s")
                .run(context -> assertThat(context).hasFailed());

        validatedRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com", "ateng.boot.mail.async.core-pool-size=0")
                .run(context -> assertThat(context).hasFailed());

        validatedRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com", "ateng.boot.mail.connection-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("ateng.boot.mail.template.html-escape 正确绑定并控制 SimpleMailTemplateRenderer 转义行为")
    void testTemplateHtmlEscapePropertyBinding() {
        contextRunner
                .withPropertyValues("ateng.boot.mail.host=smtp.example.com")
                .run(context -> {
                    MailProperties props = context.getBean(MailProperties.class);
                    assertThat(props.getTemplate().isHtmlEscape()).isFalse();
                    MailTemplateRenderer renderer = context.getBean(MailTemplateRenderer.class);
                    assertThat(renderer).isInstanceOf(SimpleMailTemplateRenderer.class);
                    assertThat(((SimpleMailTemplateRenderer) renderer).isHtmlEscape()).isFalse();
                });

        contextRunner
                .withPropertyValues(
                        "ateng.boot.mail.host=smtp.example.com",
                        "ateng.boot.mail.template.html-escape=true"
                )
                .run(context -> {
                    MailProperties props = context.getBean(MailProperties.class);
                    assertThat(props.getTemplate().isHtmlEscape()).isTrue();
                    SimpleMailTemplateRenderer renderer = (SimpleMailTemplateRenderer) context.getBean(MailTemplateRenderer.class);
                    assertThat(renderer.isHtmlEscape()).isTrue();
                    String out = renderer.render("<p>${v}</p>$!{raw}", Map.of("v", "<b>x</b>", "raw", "<i>y</i>"));
                    assertThat(out).isEqualTo("<p>&lt;b&gt;x&lt;/b&gt;</p><i>y</i>");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class DynamicProviderConfig {
        @Bean
        MailAccountProvider mailAccountProvider() {
            return accountName -> null;
        }
    }
}
