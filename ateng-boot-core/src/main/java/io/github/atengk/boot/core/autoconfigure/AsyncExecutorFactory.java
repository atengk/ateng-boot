package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 受管异步执行器实例构建工厂
 * 统一装配平台线程池与虚拟线程执行器，注入强类型参数并统一挂载四维上下文透传装饰器
 *
 * @author Ateng
 * @since 2026-10-22
 */
final class AsyncExecutorFactory {

    private AsyncExecutorFactory() {
    }

    /**
     * 构建平台线程池实例
     * <p>
     * 注意：作为 Spring 受管 Bean 注册时，由容器生命周期（{@code InitializingBean.afterPropertiesSet}）
     * 自动触发 {@code initialize()}，切勿在此处手动调用，以防御二次初始化风险。
     * </p>
     *
     * @param poolName 线程池逻辑名称（为 null 或空时使用配置类默认前缀）
     * @param pool     单池配置参数
     * @return 已配置但尚未启动底层线程池的 ThreadPoolTaskExecutor
     */
    static ThreadPoolTaskExecutor createPlatformExecutor(String poolName, AsyncPoolProperties pool) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCorePoolSize());
        executor.setMaxPoolSize(pool.getMaxPoolSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setKeepAliveSeconds(pool.getKeepAliveSeconds());
        if (StringUtils.hasText(poolName)) {
            executor.setThreadNamePrefix(pool.resolveThreadNamePrefix(poolName));
        } else {
            executor.setThreadNamePrefix(pool.getThreadNamePrefix());
        }
        executor.setRejectedExecutionHandler(pool.getRejectedPolicy() != null
                ? pool.getRejectedPolicy().getHandler()
                : new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(pool.isWaitForTasksToCompleteOnShutdown());
        executor.setAwaitTerminationSeconds(pool.getAwaitTerminationSeconds());
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        return executor;
    }

    /**
     * 构建虚拟线程执行器实例
     *
     * @param poolName 线程池逻辑名称（为 null 或空时使用配置类默认前缀）
     * @param pool     单池配置参数
     * @return 已配置的 SimpleAsyncTaskExecutor 实例
     */
    static SimpleAsyncTaskExecutor createVirtualExecutor(String poolName, AsyncPoolProperties pool) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);
        if (StringUtils.hasText(poolName)) {
            executor.setThreadNamePrefix(pool.resolveVirtualThreadNamePrefix(poolName));
        } else {
            executor.setThreadNamePrefix(pool.getVirtualThreadNamePrefix());
        }
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        if (pool.isWaitForTasksToCompleteOnShutdown() && pool.getAwaitTermination() != null) {
            executor.setTaskTerminationTimeout(pool.getAwaitTermination().toMillis());
        }
        if (pool.getConcurrencyLimit() >= 0) {
            executor.setConcurrencyLimit(pool.getConcurrencyLimit());
        }
        return executor;
    }
}
