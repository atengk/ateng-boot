package io.github.atengk.boot.job.local.config;

import io.github.atengk.boot.job.local.processor.LocalJobAnnotationBeanPostProcessor;
import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 单机定时任务核心防御性自动装配
 *
 * @author Ateng
 * @since 2026-10-25
 */
@AutoConfiguration
@ConditionalOnClass(ThreadPoolTaskScheduler.class)
@EnableConfigurationProperties(JobLocalProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.job.local", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengJobLocalAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengJobLocalAutoConfiguration.class);

    /**
     * 单机定时任务专职线程调度器 Bean
     *
     * @param properties 配置属性
     * @return ThreadPoolTaskScheduler
     */
    @Bean(name = "localJobTaskScheduler")
    @ConditionalOnMissingBean(name = "localJobTaskScheduler")
    public ThreadPoolTaskScheduler localJobTaskScheduler(JobLocalProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getPoolSize());
        scheduler.setThreadNamePrefix(properties.getThreadNamePrefix());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds((int) properties.getShutdownAwaitTerminationPeriod().toSeconds());
        scheduler.initialize();
        log.info("Initialized dedicated ThreadPoolTaskScheduler [localJobTaskScheduler] with pool size [{}] and prefix [{}]",
                properties.getPoolSize(), properties.getThreadNamePrefix());
        return scheduler;
    }

    /**
     * 单机定时任务注册中心与运行时管理器 Bean
     *
     * @param localJobTaskScheduler 专职调度器
     * @return LocalJobRegistry
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalJobRegistry localJobRegistry(ThreadPoolTaskScheduler localJobTaskScheduler) {
        return new LocalJobRegistry(localJobTaskScheduler);
    }

    /**
     * 单机定时任务统一操作门面 Bean
     *
     * @param localJobRegistry 任务注册中心
     * @return LocalJobTemplate
     */
    @Bean
    @ConditionalOnMissingBean
    public io.github.atengk.boot.job.local.template.LocalJobTemplate localJobTemplate(LocalJobRegistry localJobRegistry) {
        return new io.github.atengk.boot.job.local.template.DefaultLocalJobTemplate(localJobRegistry);
    }

    /**
     * {@link io.github.atengk.boot.job.local.annotation.LocalJob} 注解声明式扫描解析器 Bean
     * <p>
     * 声明为 static 并结合 {@link ObjectProvider} 延迟获取依赖，防止过早触发配置类与业务 Bean 的初始化。
     * </p>
     *
     * @param registryProvider 任务注册中心延迟提供者
     * @return LocalJobAnnotationBeanPostProcessor
     */
    @Bean
    @ConditionalOnMissingBean
    public static LocalJobAnnotationBeanPostProcessor localJobAnnotationBeanPostProcessor(
            org.springframework.beans.factory.ObjectProvider<LocalJobRegistry> registryProvider) {
        return new LocalJobAnnotationBeanPostProcessor(registryProvider);
    }
}
