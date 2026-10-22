package io.github.atengk.boot.core.autoconfigure;

import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.core.enums.AsyncExecutionMode;
import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import io.github.atengk.boot.core.properties.AsyncProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 配置驱动的多业务受管线程池动态注册器
 * 依据 {@code ateng.boot.async.pools} 字典为每一个业务子池动态装配受管执行器，
 * 支持平台线程与虚拟线程双模自适应，统一挂载四维上下文透传装饰器并注册 Spring 销毁钩子
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class DynamicAsyncPoolRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        if (environment == null) {
            return;
        }

        // 绑定 AsyncProperties 配置
        AsyncProperties asyncProperties = Binder.get(environment)
                .bind("ateng.boot.async", AsyncProperties.class)
                .orElseGet(AsyncProperties::new);

        // 当全局 enabled=false 时物理切断，不注册任何额外线程池
        if (!asyncProperties.isEnabled() || asyncProperties.getPools() == null || asyncProperties.getPools().isEmpty()) {
            return;
        }

        for (Map.Entry<String, AsyncPoolProperties> entry : asyncProperties.getPools().entrySet()) {
            String poolName = entry.getKey();
            AsyncPoolProperties pool = entry.getValue();

            if (!StringUtils.hasText(poolName) || pool == null || !pool.isEnabled()) {
                continue;
            }

            // 计算规范化的 Bean 名称与别名
            String beanName = poolName.endsWith("TaskExecutor") ? poolName : poolName + "TaskExecutor";
            String alias = poolName.endsWith("TaskExecutor")
                    ? poolName.substring(0, poolName.length() - "TaskExecutor".length())
                    : poolName;

            if (registry.containsBeanDefinition(beanName)) {
                log.debug("ateng-boot: 线程池 Bean [{}] 已存在，跳过动态注册", beanName);
                continue;
            }

            boolean virtualMode = isVirtualMode(pool, environment);
            BeanDefinitionBuilder builder;

            if (virtualMode) {
                builder = BeanDefinitionBuilder.genericBeanDefinition(
                        SimpleAsyncTaskExecutor.class,
                        () -> createVirtualTaskExecutor(poolName, pool)
                );
                builder.setDestroyMethodName("close");
            } else {
                builder = BeanDefinitionBuilder.genericBeanDefinition(
                        ThreadPoolTaskExecutor.class,
                        () -> createPlatformTaskExecutor(poolName, pool)
                );
                builder.setDestroyMethodName("shutdown");
            }

            registry.registerBeanDefinition(beanName, builder.getBeanDefinition());

            // 注册便于简写的别名（如别名 export 对应 Bean exportTaskExecutor）
            if (StringUtils.hasText(alias) && !alias.equals(beanName) && !registry.containsBeanDefinition(alias)) {
                registry.registerAlias(beanName, alias);
            }

            log.info("ateng-boot: 成功动态注册业务受管异步线程池 [beanName={}, alias={}, mode={}]",
                    beanName, alias, virtualMode ? "VIRTUAL" : "PLATFORM");
        }
    }

    /**
     * 判断当前线程池是否应当启用虚拟线程
     *
     * @param pool        线程池配置
     * @param environment Spring 运行时环境
     * @return 是否启用虚拟线程
     */
    private static boolean isVirtualMode(AsyncPoolProperties pool, Environment environment) {
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
            case AUTO -> Threading.VIRTUAL.isActive(environment);
        };
    }

    /**
     * 创建并初始化平台线程池
     *
     * @param poolName 线程池名称
     * @param pool     线程池配置参数
     * @return 已初始化的 ThreadPoolTaskExecutor
     */
    private static ThreadPoolTaskExecutor createPlatformTaskExecutor(String poolName, AsyncPoolProperties pool) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCorePoolSize());
        executor.setMaxPoolSize(pool.getMaxPoolSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setKeepAliveSeconds(pool.getKeepAliveSeconds());
        executor.setThreadNamePrefix(pool.resolveThreadNamePrefix(poolName));
        executor.setRejectedExecutionHandler(pool.getRejectedPolicy() != null
                ? pool.getRejectedPolicy().getHandler()
                : new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(pool.isWaitForTasksToCompleteOnShutdown());
        executor.setAwaitTerminationSeconds(pool.getAwaitTerminationSeconds());
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 创建虚拟线程执行器
     *
     * @param poolName 线程池名称
     * @param pool     线程池配置参数
     * @return SimpleAsyncTaskExecutor 实例
     */
    private static SimpleAsyncTaskExecutor createVirtualTaskExecutor(String poolName, AsyncPoolProperties pool) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);
        executor.setThreadNamePrefix(pool.resolveVirtualThreadNamePrefix(poolName));
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
