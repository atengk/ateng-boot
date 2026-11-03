package io.github.atengk.boot.liteflow.thread;

import com.yomahub.liteflow.property.LiteflowConfig;
import com.yomahub.liteflow.property.LiteflowConfigGetter;
import com.yomahub.liteflow.thread.ExecutorBuilder;
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.core.util.SpringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * LiteFlow 受管全局异步执行器构造器
 * 实现 {@link ExecutorBuilder} SPI，自动将并行 {@code WHEN} 异步调度无缝桥接至
 * Spring 受管线程池设施（{@link ThreadPoolTaskExecutor}），自动挂载四维上下文透传代理
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class LiteflowGlobalExecutorBuilder implements ExecutorBuilder, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(LiteflowGlobalExecutorBuilder.class);
    private static volatile ApplicationContext staticApplicationContext;
    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        staticApplicationContext = applicationContext;
    }

    @Override
    public ExecutorService buildExecutor() {
        ExecutorService targetExecutor = null;
        boolean sharedSpringPool = false;

        // 1. 优先尝试从 Spring 容器获取受管异步线程池设施
        ApplicationContext ctx = this.applicationContext != null ? this.applicationContext : staticApplicationContext;
        if (ctx != null) {
            try {
                ThreadPoolTaskExecutor taskExecutor = ctx.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
                targetExecutor = taskExecutor.getThreadPoolExecutor();
                sharedSpringPool = true;
                log.info("ateng-boot-liteflow: 成功将 LiteFlow 全局异步线程池接管至 Spring 受管线程池 [taskExecutor]");
            } catch (Exception e) {
                try {
                    ThreadPoolTaskExecutor taskExecutor = ctx.getBean(ThreadPoolTaskExecutor.class);
                    targetExecutor = taskExecutor.getThreadPoolExecutor();
                    sharedSpringPool = true;
                    log.info("ateng-boot-liteflow: 成功将 LiteFlow 全局异步线程池接管至 Spring 主受管线程池 [{}]",
                            taskExecutor.getThreadNamePrefix());
                } catch (Exception ex) {
                    log.debug("ateng-boot-liteflow: Spring 容器中未检测到 ThreadPoolTaskExecutor Bean，将启用独立受管线程池");
                }
            }
        } else if (SpringUtil.isInitialized()) {
            try {
                ThreadPoolTaskExecutor taskExecutor = SpringUtil.getBean("taskExecutor", ThreadPoolTaskExecutor.class);
                targetExecutor = taskExecutor.getThreadPoolExecutor();
                sharedSpringPool = true;
                log.info("ateng-boot-liteflow: 成功将 LiteFlow 全局异步线程池接管至 SpringUtil 受管线程池 [taskExecutor]");
            } catch (Exception e) {
                try {
                    ThreadPoolTaskExecutor taskExecutor = SpringUtil.getBean(ThreadPoolTaskExecutor.class);
                    targetExecutor = taskExecutor.getThreadPoolExecutor();
                    sharedSpringPool = true;
                    log.info("ateng-boot-liteflow: 成功将 LiteFlow 全局异步线程池接管至 SpringUtil 主受管线程池 [{}]",
                            taskExecutor.getThreadNamePrefix());
                } catch (Exception ex) {
                    log.debug("ateng-boot-liteflow: SpringUtil 容器中未检测到 ThreadPoolTaskExecutor Bean，将启用独立受管线程池");
                }
            }
        }

        // 2. 若未处于 Spring 容器或未定义受管线程池，提供纯净独立线程池保底
        if (targetExecutor == null) {
            LiteflowConfig config = LiteflowConfigGetter.get();
            int corePoolSize = (config != null && config.getGlobalThreadPoolSize() != null)
                    ? config.getGlobalThreadPoolSize() : 16;
            int maxPoolSize = (config != null && config.getGlobalThreadPoolSize() != null)
                    ? config.getGlobalThreadPoolSize() : 64;
            int queueCapacity = (config != null && config.getGlobalThreadPoolQueueSize() != null)
                    ? config.getGlobalThreadPoolQueueSize() : 1000;

            targetExecutor = new ThreadPoolExecutor(
                    corePoolSize,
                    maxPoolSize,
                    60L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    new NamedThreadFactory("ateng-liteflow-"),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
            log.info("ateng-boot-liteflow: 启用独立线程池 (core={}, max={}, queue={})", corePoolSize, maxPoolSize, queueCapacity);
        }

        // 3. 统一使用四维上下文透传代理包装
        return new ContextPropagatingExecutorService(targetExecutor, sharedSpringPool);
    }
}
