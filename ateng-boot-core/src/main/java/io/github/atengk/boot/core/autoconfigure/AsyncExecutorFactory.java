package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.enums.AsyncExecutionMode;
import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 受管异步执行器实例构建工厂
 * 统一装配平台线程池与虚拟线程执行器，注入强类型参数并统一挂载四维上下文透传装饰器
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public final class AsyncExecutorFactory {

    private AsyncExecutorFactory() {
    }

    /**
     * 自适应构建受管执行器实例（双模支持平台线程池与虚拟线程）
     *
     * @param poolName    线程池逻辑名称（为 null 或空时使用配置类默认前缀）
     * @param pool        单池配置参数
     * @param environment Spring 运行时环境
     * @return 已配置但尚未启动的 Executor 实例（若为 ThreadPoolTaskExecutor，Spring 容器将在 afterPropertiesSet 自动初始化）
     */
    public static Executor createExecutor(String poolName, AsyncPoolProperties pool, Environment environment) {
        if (pool == null) {
            pool = new AsyncPoolProperties();
        }
        if (isVirtualMode(pool, environment)) {
            return createVirtualExecutor(poolName, pool);
        }
        return createPlatformExecutor(poolName, pool);
    }

    /**
     * 判断当前线程池是否应当启用虚拟线程
     *
     * @param pool        线程池配置
     * @param environment Spring 运行时环境
     * @return 是否启用虚拟线程
     */
    public static boolean isVirtualMode(AsyncPoolProperties pool, Environment environment) {
        if (pool == null) {
            pool = new AsyncPoolProperties();
        }
        AsyncExecutionMode mode = pool.getMode() != null ? pool.getMode() : AsyncExecutionMode.AUTO;
        return switch (mode) {
            case VIRTUAL -> {
                if (Runtime.version().feature() < 21) {
                    log.warn("ateng-boot: 当前 JVM 版本 [{}] 低于 JDK 21，无法使用虚拟线程，线程池将回退为平台线程", Runtime.version().feature());
                    yield false;
                }
                yield true;
            }
            case PLATFORM -> false;
            case AUTO -> environment != null && Threading.VIRTUAL.isActive(environment);
        };
    }

    /**
     * 构建平台线程池实例
     * <p>
     * 注意：作为 Spring 受管 Bean 注册时，由容器生命周期（{@code InitializingBean.afterPropertiesSet}）
     * 自动触发 {@code initialize()}，切勿在此处手动调用，以防御二次初始化风险。
     * 若在脱离 Spring 容器的单测或编程式保底场景下使用，调用方需自行显式调用 {@code initialize()}。
     * </p>
     *
     * @param poolName 线程池逻辑名称（为 null 或空时使用配置类默认前缀）
     * @param pool     单池配置参数
     * @return 已配置但尚未启动底层线程池的 ThreadPoolTaskExecutor
     */
    public static ThreadPoolTaskExecutor createPlatformExecutor(String poolName, AsyncPoolProperties pool) {
        if (pool == null) {
            pool = new AsyncPoolProperties();
        }
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
    public static SimpleAsyncTaskExecutor createVirtualExecutor(String poolName, AsyncPoolProperties pool) {
        if (pool == null) {
            pool = new AsyncPoolProperties();
        }
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
