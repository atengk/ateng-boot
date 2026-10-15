package io.github.atengk.boot.web.autoconfigure;

import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.web.properties.AsyncProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 上下文感知受管异步线程池自动装配
 * 导出预装配 {@link ContextCopyingTaskDecorator} 的 {@link ThreadPoolTaskExecutor}，
 * 并实现 {@link AsyncConfigurer} 为 Spring 异步任务体系统一提供上下文流转与未捕获异常处理
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Slf4j
@AutoConfiguration(before = org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration.class)
@ConditionalOnClass(ThreadPoolTaskExecutor.class)
@EnableConfigurationProperties(AsyncProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.async", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengAsyncAutoConfiguration implements AsyncConfigurer {

    private final AsyncProperties asyncProperties;
    private final ObjectProvider<Executor> taskExecutorProvider;

    public AtengAsyncAutoConfiguration(AsyncProperties asyncProperties,
                                      @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
        this.asyncProperties = asyncProperties;
        this.taskExecutorProvider = taskExecutorProvider;
    }

    /**
     * 注册核心受管异步线程池，装备上下文传播装饰器、CallerRunsPolicy 饱和策略与优雅停机策略
     *
     * @return ThreadPoolTaskExecutor 实例
     */
    @Bean(name = {"taskExecutor", "applicationTaskExecutor"})
    @ConditionalOnMissingBean(name = {"taskExecutor", "applicationTaskExecutor"})
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncProperties.getCorePoolSize());
        executor.setMaxPoolSize(asyncProperties.getMaxPoolSize());
        executor.setQueueCapacity(asyncProperties.getQueueCapacity());
        executor.setKeepAliveSeconds(asyncProperties.getKeepAliveSeconds());
        executor.setThreadNamePrefix(asyncProperties.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(asyncProperties.isWaitForTasksToCompleteOnShutdown());
        executor.setAwaitTerminationSeconds(asyncProperties.getAwaitTerminationSeconds());
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.initialize();
        return executor;
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
