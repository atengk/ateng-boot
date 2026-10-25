package io.github.atengk.boot.job.local.config;

import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.job.local.actuator.LocalJobEndpoint;
import io.github.atengk.boot.job.local.alert.JobAlertHandler;
import io.github.atengk.boot.job.local.alert.LocalJobAlertListener;
import io.github.atengk.boot.job.local.alert.LoggingJobAlertHandler;
import io.github.atengk.boot.job.local.alert.NotificationJobAlertHandler;
import io.github.atengk.boot.job.local.lifecycle.LocalJobLifecycleManager;
import io.github.atengk.boot.job.local.lock.JobClusterLockProvider;
import io.github.atengk.boot.job.local.lock.LocalJobClusterLockProvider;
import io.github.atengk.boot.job.local.processor.LocalJobAnnotationBeanPostProcessor;
import io.github.atengk.boot.job.local.properties.JobLocalProperties;
import io.github.atengk.boot.job.local.registry.LocalJobRegistry;
import io.github.atengk.boot.job.local.storage.InMemoryJobLogStorageProvider;
import io.github.atengk.boot.job.local.storage.InMemoryJobMetadataRepository;
import io.github.atengk.boot.job.local.storage.JobLogStorageProvider;
import io.github.atengk.boot.job.local.storage.JobMetadataRepository;
import io.github.atengk.boot.job.local.template.DefaultLocalJobTemplate;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
     * 单机定时任务执行 Worker 专职线程池 Bean（专用于任务超时控制与 Worker 执行隔离，统一挂载上下文装饰器）
     *
     * @param properties 配置属性
     * @return ThreadPoolTaskExecutor
     */
    @Bean(name = "localJobWorkerExecutor")
    @ConditionalOnMissingBean(name = "localJobWorkerExecutor")
    public ThreadPoolTaskExecutor localJobWorkerExecutor(JobLocalProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getPoolSize());
        executor.setMaxPoolSize(properties.getPoolSize() * 2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ateng-job-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.getShutdownAwaitTerminationPeriod().toSeconds());
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.initialize();
        log.info("Initialized dedicated ThreadPoolTaskExecutor [localJobWorkerExecutor] with core pool size [{}] and prefix [{}]",
                properties.getPoolSize(), "ateng-job-worker-");
        return executor;
    }

    /**
     * 任务元数据存储与配置仲裁 SPI 默认内存实现 Bean
     *
     * @return JobMetadataRepository
     */
    @Bean
    @ConditionalOnMissingBean
    public JobMetadataRepository jobMetadataRepository() {
        return new InMemoryJobMetadataRepository();
    }

    /**
     * 任务执行历史日志存储 SPI 默认有界内存环形队列 Bean（防 OOM 自动 FIFO 淘汰）
     *
     * @param properties 配置属性
     * @return JobLogStorageProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public JobLogStorageProvider jobLogStorageProvider(JobLocalProperties properties) {
        return new InMemoryJobLogStorageProvider(properties.getInMemoryLogCapacity());
    }

    /**
     * 单机定时任务集群互斥锁 SPI 默认本地兜底实现 Bean
     *
     * @return JobClusterLockProvider
     */
    @Bean
    @ConditionalOnMissingBean
    public JobClusterLockProvider jobClusterLockProvider() {
        return new LocalJobClusterLockProvider();
    }

    /**
     * 单机定时任务注册中心与运行时管理器 Bean
     *
     * @param localJobTaskScheduler  专职调度器
     * @param workerExecutorProvider Worker 线程池提供者
     * @param metadataRepository     元数据存储仓储
     * @param logStorageProvider     历史日志存储提供者
     * @param clusterLockProvider    集群互斥锁提供者
     * @param properties             配置属性
     * @return LocalJobRegistry
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalJobRegistry localJobRegistry(
            ThreadPoolTaskScheduler localJobTaskScheduler,
            @Qualifier("localJobWorkerExecutor") ObjectProvider<ThreadPoolTaskExecutor> workerExecutorProvider,
            JobMetadataRepository metadataRepository,
            JobLogStorageProvider logStorageProvider,
            JobClusterLockProvider clusterLockProvider,
            JobLocalProperties properties) {
        return new LocalJobRegistry(localJobTaskScheduler, workerExecutorProvider.getIfAvailable(),
                metadataRepository, logStorageProvider, clusterLockProvider, properties);
    }

    /**
     * 单机定时任务生命周期与优雅停机编排管理器 Bean（赋予 SmartLifecycle 最高阶段延迟激活调度）
     *
     * @param localJobRegistry 任务注册中心
     * @param properties       配置属性
     * @return LocalJobLifecycleManager
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalJobLifecycleManager localJobLifecycleManager(LocalJobRegistry localJobRegistry, JobLocalProperties properties) {
        return new LocalJobLifecycleManager(localJobRegistry, properties);
    }

    /**
     * 单机定时任务统一操作门面 Bean
     *
     * @param localJobRegistry 任务注册中心
     * @return LocalJobTemplate
     */
    @Bean
    @ConditionalOnMissingBean
    public LocalJobTemplate localJobTemplate(LocalJobRegistry localJobRegistry) {
        return new DefaultLocalJobTemplate(localJobRegistry);
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
            ObjectProvider<LocalJobRegistry> registryProvider) {
        return new LocalJobAnnotationBeanPostProcessor(registryProvider);
    }

    /**
     * Actuator 运维管控端点自动装配（当类路径存在 Actuator Endpoint 且未显式禁用时装配）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnProperty(prefix = "ateng.boot.job.local.actuator", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class LocalJobActuatorAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public LocalJobEndpoint localJobEndpoint(LocalJobTemplate localJobTemplate) {
            return new LocalJobEndpoint(localJobTemplate);
        }
    }

    /**
     * 任务异常失败与超时告警桥接自动装配（当 ateng.boot.job.local.alert.enabled=true 时装配）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.boot.job.local.alert", name = "enabled", havingValue = "true")
    static class LocalJobAlertAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public JobAlertHandler jobAlertHandler(
                ObjectProvider<NotificationTemplate> notificationTemplateProvider,
                JobLocalProperties properties) {
            NotificationTemplate notificationTemplate = notificationTemplateProvider.getIfAvailable();
            if (notificationTemplate != null) {
                return new NotificationJobAlertHandler(notificationTemplate, properties.getAlert().getChannel());
            }
            return new LoggingJobAlertHandler();
        }

        @Bean
        @ConditionalOnMissingBean
        public LocalJobAlertListener localJobAlertListener(JobAlertHandler jobAlertHandler) {
            return new LocalJobAlertListener(jobAlertHandler);
        }
    }
}
