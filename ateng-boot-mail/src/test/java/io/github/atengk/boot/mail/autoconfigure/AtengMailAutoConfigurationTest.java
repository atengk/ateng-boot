package io.github.atengk.boot.mail.autoconfigure;

import io.github.atengk.boot.mail.handler.MailSendFailureHandler;
import io.github.atengk.boot.mail.interceptor.MailSendInterceptor;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.thymeleaf.spring6.SpringTemplateEngine;

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
}
