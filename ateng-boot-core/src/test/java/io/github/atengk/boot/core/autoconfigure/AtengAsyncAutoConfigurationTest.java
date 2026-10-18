package io.github.atengk.boot.core.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import io.github.atengk.boot.core.properties.AsyncProperties;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文感知受管异步线程池自动装配单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("上下文感知受管异步线程池自动装配测试")
class AtengAsyncAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengAsyncAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功装配 taskExecutor 与 applicationTaskExecutor，属性自适应 CPU 核心数")
    void shouldAutoConfigureDefaultAsyncExecutor() {
        int expectedCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        contextRunner.run(context -> {
            assertThat(context).hasBean("taskExecutor");
            assertThat(context).hasBean("applicationTaskExecutor");
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            assertThat(context).hasSingleBean(AsyncConfigurer.class);

            ThreadPoolTaskExecutor executor = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(expectedCores);
            assertThat(executor.getMaxPoolSize()).isEqualTo(expectedCores * 2);
            assertThat(executor.getKeepAliveSeconds()).isEqualTo(60);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("ateng-async-");

            AsyncConfigurer configurer = context.getBean(AsyncConfigurer.class);
            assertThat(configurer.getAsyncExecutor()).isSameAs(executor);
            assertThat(configurer.getAsyncUncaughtExceptionHandler()).isNotNull();
        });
    }

    @Test
    @DisplayName("配置自定义 ateng.boot.async.* 属性时成功覆盖默认线程池参数")
    void shouldApplyCustomConfigurationProperties() {
        contextRunner.withPropertyValues(
                "ateng.boot.async.core-pool-size=4",
                "ateng.boot.async.max-pool-size=8",
                "ateng.boot.async.queue-capacity=200",
                "ateng.boot.async.keep-alive-seconds=30",
                "ateng.boot.async.thread-name-prefix=custom-async-"
        ).run(context -> {
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor executor = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(8);
            assertThat(executor.getKeepAliveSeconds()).isEqualTo(30);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("custom-async-");
        });
    }

    @Test
    @DisplayName("配置强类型 Duration 格式参数时成功解析并在关闭时优雅等待")
    void shouldSupportStronglyTypedDurationProperties() {
        contextRunner.withPropertyValues(
                "ateng.boot.async.keep-alive=45s",
                "ateng.boot.async.await-termination=90s",
                "ateng.boot.async.wait-for-tasks-to-complete-on-shutdown=true"
        ).run(context -> {
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor executor = context.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getKeepAliveSeconds()).isEqualTo(45);

            AsyncProperties properties = context.getBean(AsyncProperties.class);
            assertThat(properties.getKeepAlive()).isEqualTo(java.time.Duration.ofSeconds(45));
            assertThat(properties.getAwaitTermination()).isEqualTo(java.time.Duration.ofSeconds(90));
        });
    }

    @Test
    @DisplayName("配置 ateng.boot.async.enabled=false 时禁用异步线程池装配")
    void shouldDisableAsyncAutoConfigurationWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.async.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("taskExecutor");
                    assertThat(context).doesNotHaveBean(AtengAsyncAutoConfiguration.class);
                });
    }

    @Test
    @DisplayName("当下游应用自定义 taskExecutor Bean 时自动装配防御性退避")
    void shouldBackOffWhenCustomTaskExecutorBeanExists() {
        contextRunner.withUserConfiguration(CustomExecutorConfig.class)
                .run(context -> {
                    assertThat(context).hasBean("taskExecutor");
                    Executor executor = context.getBean("taskExecutor", Executor.class);
                    assertThat(executor).isSameAs(CustomExecutorConfig.CUSTOM_EXECUTOR);
                });
    }

    @Test
    @DisplayName("开启 spring.threads.virtual.enabled=true 时在 JDK 21+ 下成功装配 SimpleAsyncTaskExecutor 虚拟线程执行器")
    @EnabledForJreRange(min = JRE.JAVA_21)
    void shouldAutoConfigureVirtualThreadAsyncExecutorWhenEnabled() {
        contextRunner.withPropertyValues("spring.threads.virtual.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("taskExecutor");
                    assertThat(context).hasBean("applicationTaskExecutor");
                    assertThat(context).hasSingleBean(SimpleAsyncTaskExecutor.class);
                    assertThat(context).doesNotHaveBean(ThreadPoolTaskExecutor.class);
                    assertThat(context).hasSingleBean(AsyncConfigurer.class);

                    SimpleAsyncTaskExecutor executor = context.getBean("taskExecutor", SimpleAsyncTaskExecutor.class);
                    assertThat(executor.getThreadNamePrefix()).isEqualTo("ateng-vt-async-");
                    assertThat(executor.getConcurrencyLimit()).isEqualTo(SimpleAsyncTaskExecutor.UNBOUNDED_CONCURRENCY);

                    AsyncConfigurer configurer = context.getBean(AsyncConfigurer.class);
                    assertThat(configurer.getAsyncExecutor()).isSameAs(executor);
                });
    }

    @Test
    @DisplayName("开启虚拟线程并配置自定义 virtual-thread-name-prefix 与 concurrency-limit 时属性成功生效")
    @EnabledForJreRange(min = JRE.JAVA_21)
    void shouldApplyCustomVirtualThreadProperties() {
        contextRunner.withPropertyValues(
                "spring.threads.virtual.enabled=true",
                "ateng.boot.async.virtual-thread-name-prefix=custom-vt-",
                "ateng.boot.async.concurrency-limit=64"
        ).run(context -> {
            assertThat(context).hasSingleBean(SimpleAsyncTaskExecutor.class);
            SimpleAsyncTaskExecutor executor = context.getBean("taskExecutor", SimpleAsyncTaskExecutor.class);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("custom-vt-");
            assertThat(executor.getConcurrencyLimit()).isEqualTo(64);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomExecutorConfig {
        static final ThreadPoolTaskExecutor CUSTOM_EXECUTOR = new ThreadPoolTaskExecutor();

        static {
            CUSTOM_EXECUTOR.setThreadNamePrefix("user-defined-");
        }

        @Bean(name = "taskExecutor")
        public Executor taskExecutor() {
            return CUSTOM_EXECUTOR;
        }
    }
}
