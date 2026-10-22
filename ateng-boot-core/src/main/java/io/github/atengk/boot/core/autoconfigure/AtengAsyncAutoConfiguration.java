package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.properties.AsyncProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * 上下文感知自适应双模受管异步任务执行器自动装配
 * 依据运行环境线程模型（{@link ConditionalOnThreading}）自适应装配虚拟线程执行器（{@link SimpleAsyncTaskExecutor}）
 * 或平台线程池（{@link ThreadPoolTaskExecutor}），统一挂载 {@link ContextCopyingTaskDecorator}
 * 并实现 {@link AsyncConfigurer} 为 Spring 异步任务体系统一提供四维上下文流转与未捕获异常处理
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Slf4j
@AutoConfiguration(before = org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration.class)
@ConditionalOnClass(ThreadPoolTaskExecutor.class)
@EnableConfigurationProperties(AsyncProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.async", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({
        AtengAsyncAutoConfiguration.VirtualThreadsConfiguration.class,
        AtengAsyncAutoConfiguration.PlatformThreadsConfiguration.class,
        DynamicAsyncPoolRegistrar.class
})
public class AtengAsyncAutoConfiguration implements AsyncConfigurer {

    private final ObjectProvider<Executor> taskExecutorProvider;

    public AtengAsyncAutoConfiguration(@Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
        this.taskExecutorProvider = taskExecutorProvider;
    }

    /**
     * 虚拟线程执行器装配配置类
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnThreading(Threading.VIRTUAL)
    static class VirtualThreadsConfiguration {

        @Bean(name = {"taskExecutor", "applicationTaskExecutor"})
        @Primary
        @ConditionalOnMissingBean(name = {"taskExecutor", "applicationTaskExecutor"})
        public SimpleAsyncTaskExecutor virtualTaskExecutor(AsyncProperties asyncProperties) {
            return AsyncExecutorFactory.createVirtualExecutor(null, asyncProperties);
        }
    }

    /**
     * 平台线程池装配配置类
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnThreading(Threading.PLATFORM)
    static class PlatformThreadsConfiguration {

        @Bean(name = {"taskExecutor", "applicationTaskExecutor"})
        @Primary
        @ConditionalOnMissingBean(name = {"taskExecutor", "applicationTaskExecutor"})
        public ThreadPoolTaskExecutor platformTaskExecutor(AsyncProperties asyncProperties) {
            return AsyncExecutorFactory.createPlatformExecutor(null, asyncProperties);
        }
    }

    @Override
    public Executor getAsyncExecutor() {
        return taskExecutorProvider.getIfAvailable();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                log.error("异步任务执行发生未捕获异常: 方法 [{}], 参数 [{}]", method.getName(), Arrays.toString(params), throwable);
    }
}
