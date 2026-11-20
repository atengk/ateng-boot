package io.github.atengk.boot.job.snail.autoconfigure;

import com.aizuda.snailjob.client.common.config.SnailJobProperties.ServerConfig;
import com.aizuda.snailjob.client.core.Scanner;
import com.aizuda.snailjob.client.core.intercepter.SnailRetryInterceptor;
import com.aizuda.snailjob.client.core.openapi.RetryOpenApiClientV2;
import com.aizuda.snailjob.client.core.strategy.LocalRetryStrategies;
import com.aizuda.snailjob.client.job.core.openapi.JobOpenApiClientV2;
import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.job.snail.actuator.SnailJobEndpoint;
import io.github.atengk.boot.job.snail.actuator.SnailJobHealthIndicator;
import io.github.atengk.boot.job.snail.alert.SnailJobAlertAggregator;
import io.github.atengk.boot.job.snail.aspect.SnailJobContextAspect;
import io.github.atengk.boot.job.snail.drain.GracefulDrainStateMachine;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper;
import io.github.atengk.boot.job.snail.interceptor.SnailJobInterceptor;
import io.github.atengk.boot.job.snail.log.SnailJobLogbackAppenderRegistrar;
import io.github.atengk.boot.job.snail.metrics.SnailJobMetricsBinder;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.retry.aspect.AtengSnailRetryInterceptor;
import io.github.atengk.boot.job.snail.retry.aspect.AtengSnailRetryPointcutAdvisor;
import io.github.atengk.boot.job.snail.retry.aspect.SnailRetryContextAspect;
import io.github.atengk.boot.job.snail.retry.callback.SnailRetryDeadLetterCallback;
import io.github.atengk.boot.job.snail.retry.context.InMemorySnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.context.RedisSnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextStore;
import io.github.atengk.boot.job.snail.retry.generator.ArgsHashIdempotentIdGenerator;
import io.github.atengk.boot.job.snail.retry.generator.SpelIdempotentIdGenerator;
import io.github.atengk.boot.job.snail.retry.scanner.AtengSnailRetryScanner;
import io.github.atengk.boot.job.snail.retry.template.SnailRetryTemplate;
import io.github.atengk.boot.job.snail.scanner.AtengSnailJobScanner;
import io.github.atengk.boot.job.snail.template.BatchFailureHandler;
import io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate;
import io.github.atengk.boot.job.snail.template.SnailMyBatisStreamingTemplate;
import io.github.atengk.boot.job.snail.template.SnailShardingBatchTemplate;
import io.github.atengk.boot.job.snail.template.TenantJobTemplate;
import io.github.atengk.boot.job.snail.tenant.TenantListProvider;
import io.github.atengk.boot.job.snail.watchdog.JobTimeoutFallbackHandler;
import io.github.atengk.boot.job.snail.watchdog.SnailLocalWatchdog;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.concurrent.Executor;

/**
 * SnailJob 分布式任务调度与分布式重试双擎自动装配入口
 *
 * <p>遵循 Spring Boot 3 规范与防御性装配四道铁律：
 * 1. 标准规范注册于 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}；
 * 2. 缺配置平滑探测：当未配置关键外部服务端连接信息时，自动告警并平滑旁路，严禁应用启动崩溃；
 * 3. 每一个公开 {@code @Bean} 严格标配 {@link ConditionalOnMissingBean}，支持业务方平滑接管；
 * 4. 显式声明在 {@link AtengAsyncAutoConfiguration} 之后装配，保障受管线程池设施优先就绪。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@AutoConfiguration(
        after = AtengAsyncAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
)
@ConditionalOnClass(com.aizuda.snailjob.client.common.config.SnailJobProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.job.snail", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SnailJobProperties.class)
public class AtengSnailJobAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengSnailJobAutoConfiguration.class);

    /**
     * SnailJob 配置属性桥接适配器
     *
     * <p>将 {@code ateng.boot.job.snail.*} 强类型配置平滑同步映射至 SnailJob 原生客户端属性模型，
     * 支持服务端地址缺省探测与静默旁路保护。</p>
     *
     * @param properties ateng SnailJob 统一配置属性
     * @param nativePropertiesProvider SnailJob 官方原生配置属性提供者
     * @param environment Spring 运行期环境变量
     * @return 属性桥接适配器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SnailJobPropertiesBridge snailJobPropertiesBridge(
            SnailJobProperties properties,
            ObjectProvider<com.aizuda.snailjob.client.common.config.SnailJobProperties> nativePropertiesProvider,
            Environment environment) {

        if (!StringUtils.hasText(properties.getServer())) {
            log.warn("SnailJob 自动装配已激活，但未检测到有效服务端地址 ('ateng.boot.job.snail.server' 为空)，已平滑旁路跳过。");
            return new SnailJobPropertiesBridge(properties, false);
        }

        com.aizuda.snailjob.client.common.config.SnailJobProperties nativeProps = nativePropertiesProvider.getIfAvailable();
        if (nativeProps != null) {
            syncNativeProperties(properties, nativeProps, environment);
        }

        log.info("SnailJob 双擎加固底座初始化就绪 [Server: {}, Namespace: {}, Group: {}, Port: {}]",
                properties.getServer(),
                properties.getNamespace(),
                StringUtils.hasText(properties.getGroup()) ? properties.getGroup() : "<默认应用名>",
                properties.getPort());

        return new SnailJobPropertiesBridge(properties, true);
    }

    /**
     * SnailJob 执行器三阶平滑停机排空状态机
     *
     * @param properties SnailJob 配置属性
     * @return GracefulDrainStateMachine 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public GracefulDrainStateMachine snailJobGracefulDrainStateMachine(SnailJobProperties properties) {
        return new GracefulDrainStateMachine(properties);
    }

    /**
     * SnailJob 脱网离线单元测试与端到端调试辅助门面
     *
     * @return SnailJobTestHelper 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SnailJobTestHelper snailJobTestHelper() {
        return new SnailJobTestHelper();
    }

    /**
     * SnailJob 节点级任务运行时动态治理总线
     *
     * @return SnailJobRuntimeGovernor 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SnailJobRuntimeGovernor snailJobRuntimeGovernor() {
        return new SnailJobRuntimeGovernor();
    }

    /**
     * SnailJob 三阶告警防风暴聚合与自愈引擎
     *
     * @param properties                   SnailJob 配置属性
     * @param eventPublisher               Spring 领域事件发布器
     * @param notificationTemplateProvider 协同通知组件提供者 (可选)
     * @return SnailJobAlertAggregator 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.snail.alert", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SnailJobAlertAggregator snailJobAlertAggregator(
            SnailJobProperties properties,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<NotificationTemplate> notificationTemplateProvider) {
        return new SnailJobAlertAggregator(properties, eventPublisher, notificationTemplateProvider.getIfAvailable());
    }

    /**
     * SnailJob 调度与重试 Micrometer 核心监控度量绑定器
     *
     * @param drainStateMachineProvider 平滑排空状态机提供者 (可选)
     * @return SnailJobMetricsBinder 实例
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnMissingBean
    public SnailJobMetricsBinder snailJobMetricsBinder(
            ObjectProvider<GracefulDrainStateMachine> drainStateMachineProvider) {
        return new SnailJobMetricsBinder(drainStateMachineProvider.getIfAvailable());
    }

    /**
     * SnailJob 任务调度与分布式重试运维管控与本地手动触发 Actuator 端点
     *
     * @param properties                SnailJob 配置属性
     * @param drainStateMachineProvider 平滑排空状态机提供者 (可选)
     * @param governorProvider          节点级运行时治理总线提供者 (可选)
     * @return SnailJobEndpoint 实例
     */
    @Bean
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnMissingBean
    public SnailJobEndpoint snailJobEndpoint(
            SnailJobProperties properties,
            ObjectProvider<GracefulDrainStateMachine> drainStateMachineProvider,
            ObjectProvider<SnailJobRuntimeGovernor> governorProvider) {
        return new SnailJobEndpoint(
                properties,
                drainStateMachineProvider.getIfAvailable(),
                governorProvider.getIfAvailable()
        );
    }

    /**
     * SnailJob 分布式任务调度与分布式重试双擎健康探针
     *
     * @param properties                SnailJob 配置属性
     * @param drainStateMachineProvider 平滑排空状态机提供者 (可选)
     * @param taskExecutorProvider      Core 受管线程池提供者 (可选)
     * @return SnailJobHealthIndicator 实例
     */
    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "snailJobHealthIndicator")
    public SnailJobHealthIndicator snailJobHealthIndicator(
            SnailJobProperties properties,
            ObjectProvider<GracefulDrainStateMachine> drainStateMachineProvider,
            @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
        return new SnailJobHealthIndicator(
                properties,
                drainStateMachineProvider.getIfAvailable(),
                resolveManagedExecutor(properties, taskExecutorProvider)
        );
    }

    /**
     * SnailJob 官方 OpenAPI 编程式管理门面
     *
     * @param jobClientProvider    SnailJob 任务 OpenAPI 客户端提供者 (可选)
     * @param retryClientProvider  SnailJob 重试 OpenAPI 客户端提供者 (可选)
     * @param properties           SnailJob 全局配置属性
     * @param taskExecutorProvider Core 受管线程池提供者 (可选)
     * @return SnailJobAdminTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.snail.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SnailJobAdminTemplate snailJobAdminTemplate(
            ObjectProvider<JobOpenApiClientV2> jobClientProvider,
            ObjectProvider<RetryOpenApiClientV2> retryClientProvider,
            SnailJobProperties properties,
            @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
        JobOpenApiClientV2 jobClient = jobClientProvider.getIfAvailable();
        RetryOpenApiClientV2 retryClient = retryClientProvider.getIfAvailable();
        Executor managedExecutor = resolveManagedExecutor(properties, taskExecutorProvider);
        return new SnailJobAdminTemplate(jobClient, retryClient, properties, managedExecutor);
    }

    /**
     * SnailJob Logback 动态日志镜像追加器自动注册器
     *
     * @return SnailJobLogbackAppenderRegistrar 实例
     */
    @Bean
    @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.boot.job.snail", name = "log-mirror-enabled", havingValue = "true", matchIfMissing = true)
    public SnailJobLogbackAppenderRegistrar snailJobLogbackAppenderRegistrar(SnailJobProperties properties) {
        return new SnailJobLogbackAppenderRegistrar(properties);
    }

    /**
     * 任务调度引擎子装配（受 {@code ateng.boot.job.snail.job.enabled} 门控）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.boot.job.snail.job", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class JobEngineConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SnailLocalWatchdog snailLocalWatchdog(
                ApplicationEventPublisher eventPublisher,
                SnailJobProperties properties,
                ObjectProvider<JobTimeoutFallbackHandler> fallbackHandlersProvider,
                @Qualifier("taskExecutor") ObjectProvider<Executor> asyncExecutorProvider) {
            return new SnailLocalWatchdog(
                    eventPublisher,
                    null,
                    properties.getWatchdogGracePeriod(),
                    fallbackHandlersProvider.orderedStream().toList(),
                    resolveManagedExecutor(properties, asyncExecutorProvider)
            );
        }

        @Bean
        @ConditionalOnClass(com.aizuda.snailjob.client.job.core.Scanner.class)
        @ConditionalOnMissingBean
        public AtengSnailJobScanner atengSnailJobScanner() {
            return new AtengSnailJobScanner();
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.boot.job.snail.job", name = "context-aspect-enabled", havingValue = "true", matchIfMissing = true)
        public SnailJobContextAspect snailJobContextAspect(
                SnailLocalWatchdog watchdog,
                SnailJobProperties properties,
                ObjectProvider<GracefulDrainStateMachine> drainStateMachineProvider,
                ObjectProvider<SnailJobMetricsBinder> metricsBinderProvider,
                ObjectProvider<SnailJobAlertAggregator> alertAggregatorProvider,
                ObjectProvider<SnailJobInterceptor> interceptorsProvider,
                ObjectProvider<JobTimeoutFallbackHandler> fallbackHandlersProvider,
                ObjectProvider<SnailJobRuntimeGovernor> governorProvider,
                @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
            return new SnailJobContextAspect(
                    watchdog,
                    properties,
                    drainStateMachineProvider.getIfAvailable(),
                    metricsBinderProvider.getIfAvailable(),
                    alertAggregatorProvider.getIfAvailable(),
                    interceptorsProvider.orderedStream().toList(),
                    fallbackHandlersProvider.orderedStream().toList(),
                    governorProvider.getIfAvailable(),
                    resolveManagedExecutor(properties, taskExecutorProvider)
            );
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = {
                "io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate",
                "org.apache.ibatis.cursor.Cursor"
        })
        public static class SnailJobMyBatisConfiguration {

            @Bean
            @ConditionalOnMissingBean(SnailShardingBatchTemplate.class)
            @ConditionalOnProperty(prefix = "ateng.boot.job.snail.job", name = "batch-template-enabled", havingValue = "true", matchIfMissing = true)
            public SnailShardingBatchTemplate snailMyBatisStreamingTemplate(
                    @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
                    SnailJobProperties properties,
                    ObjectProvider<BatchFailureHandler<?>> batchFailureHandlerProvider) {
                SnailJobProperties.BatchProperties batch = properties.getBatch();
                return new SnailMyBatisStreamingTemplate(
                        resolveManagedExecutor(properties, taskExecutorProvider),
                        batch.getDefaultBatchSize(),
                        batch.getDefaultMaxConcurrency(),
                        true,
                        properties.isManagedPoolEnabled(),
                        batch.getProgressLogInterval(),
                        batch.getMaxFailedItems(),
                        null,
                        batchFailureHandlerProvider.getIfAvailable()
                );
            }
        }

        @Bean
        @ConditionalOnMissingBean(SnailShardingBatchTemplate.class)
        @ConditionalOnProperty(prefix = "ateng.boot.job.snail.job", name = "batch-template-enabled", havingValue = "true", matchIfMissing = true)
        public SnailShardingBatchTemplate snailShardingBatchTemplate(
                @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
                SnailJobProperties properties,
                ObjectProvider<BatchFailureHandler<?>> batchFailureHandlerProvider) {
            SnailJobProperties.BatchProperties batch = properties.getBatch();
            return new SnailShardingBatchTemplate(
                    resolveManagedExecutor(properties, taskExecutorProvider),
                    batch.getDefaultBatchSize(),
                    batch.getDefaultMaxConcurrency(),
                    true,
                    properties.isManagedPoolEnabled(),
                    batch.getProgressLogInterval(),
                    batch.getMaxFailedItems(),
                    batchFailureHandlerProvider.getIfAvailable()
            );
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.boot.job.snail.job", name = "tenant-template-enabled", havingValue = "true", matchIfMissing = true)
        public TenantJobTemplate tenantJobTemplate(
                ObjectProvider<TenantListProvider> tenantListProvider,
                ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
                @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutorProvider,
                SnailJobProperties properties,
                ObjectProvider<BatchFailureHandler<?>> batchFailureHandlerProvider) {
            SnailJobProperties.BatchProperties batch = properties.getBatch();
            return new TenantJobTemplate(
                    tenantListProvider.getIfAvailable(),
                    transactionManagerProvider,
                    resolveManagedExecutor(properties, taskExecutorProvider),
                    batch.getDefaultMaxConcurrency(),
                    properties.isManagedPoolEnabled(),
                    batch.getMaxFailedItems(),
                    batchFailureHandlerProvider.getIfAvailable()
            );
        }
    }

    /**
     * 分布式重试引擎子装配（受 {@code ateng.boot.job.snail.retry.enabled} 门控）
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ateng.boot.job.snail.retry", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class RetryEngineConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SpelIdempotentIdGenerator spelIdempotentIdGenerator() {
            return new SpelIdempotentIdGenerator();
        }

        @Bean
        @ConditionalOnMissingBean
        public ArgsHashIdempotentIdGenerator argsHashIdempotentIdGenerator() {
            return new ArgsHashIdempotentIdGenerator();
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(StringRedisTemplate.class)
        static class RedisSnailRetryContextStoreConfiguration {

            @Bean
            @ConditionalOnBean(StringRedisTemplate.class)
            @ConditionalOnMissingBean(SnailRetryContextStore.class)
            public SnailRetryContextStore redisSnailRetryContextStore(
                    StringRedisTemplate stringRedisTemplate,
                    SnailJobProperties properties) {
                return new RedisSnailRetryContextStore(stringRedisTemplate, properties);
            }
        }

        @Bean
        @ConditionalOnMissingBean(SnailRetryContextStore.class)
        public SnailRetryContextStore snailRetryContextStore(SnailJobProperties properties) {
            SnailJobProperties.RetryProperties retry = (properties != null && properties.getRetry() != null)
                    ? properties.getRetry()
                    : new SnailJobProperties.RetryProperties();
            return new InMemorySnailRetryContextStore(retry.getInMemoryMaxSize(), retry.getContextTtl());
        }

        @Bean
        @ConditionalOnClass(Scanner.class)
        @ConditionalOnMissingBean
        public AtengSnailRetryScanner atengSnailRetryScanner() {
            return new AtengSnailRetryScanner();
        }

        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        @ConditionalOnClass({SnailRetryInterceptor.class, LocalRetryStrategies.class})
        @ConditionalOnBean(LocalRetryStrategies.class)
        @ConditionalOnMissingBean(name = "atengSnailRetryPointcutAdvisor")
        public AtengSnailRetryPointcutAdvisor atengSnailRetryPointcutAdvisor(
                Environment environment,
                @Lazy LocalRetryStrategies localRetryStrategies) {
            int order = environment.getProperty("snail-job.aop.order", Integer.class, Ordered.HIGHEST_PRECEDENCE);
            return new AtengSnailRetryPointcutAdvisor(new AtengSnailRetryInterceptor(order, localRetryStrategies));
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ateng.boot.job.snail.retry", name = "context-aspect-enabled", havingValue = "true", matchIfMissing = true)
        public SnailRetryContextAspect snailRetryContextAspect(
                ObjectProvider<GracefulDrainStateMachine> drainStateMachineProvider,
                ObjectProvider<SnailJobMetricsBinder> metricsBinderProvider,
                ObjectProvider<SnailJobAlertAggregator> alertAggregatorProvider,
                ObjectProvider<SnailRetryContextStore> retryContextStoreProvider) {
            return new SnailRetryContextAspect(
                    drainStateMachineProvider.getIfAvailable(),
                    metricsBinderProvider.getIfAvailable(),
                    alertAggregatorProvider.getIfAvailable(),
                    retryContextStoreProvider.getIfAvailable()
            );
        }

        @Bean
        @ConditionalOnMissingBean
        public SnailRetryTemplate snailRetryTemplate(
                @Qualifier("syncReport") ObjectProvider<com.aizuda.snailjob.client.core.Report> syncReportProvider,
                @Qualifier("asyncReport") ObjectProvider<com.aizuda.snailjob.client.core.Report> asyncReportProvider,
                ObjectProvider<com.aizuda.snailjob.client.core.Report> reportProvider,
                SnailJobProperties properties,
                ObjectProvider<SnailRetryContextStore> contextStoreProvider) {
            com.aizuda.snailjob.client.core.Report preferredReport = syncReportProvider.getIfAvailable();
            if (preferredReport == null) {
                preferredReport = asyncReportProvider.getIfAvailable();
            }
            if (preferredReport == null) {
                preferredReport = reportProvider.orderedStream().findFirst().orElse(null);
            }
            return new SnailRetryTemplate(reportProvider, preferredReport, properties, contextStoreProvider.getIfAvailable());
        }

        @Bean
        @ConditionalOnMissingBean(SnailRetryDeadLetterCallback.class)
        public SnailRetryDeadLetterCallback snailRetryDeadLetterCallback(
                ObjectProvider<SnailJobAlertAggregator> alertAggregatorProvider,
                ObjectProvider<SnailRetryContextStore> contextStoreProvider) {
            return new SnailRetryDeadLetterCallback(alertAggregatorProvider, contextStoreProvider);
        }
    }

    private static Executor resolveManagedExecutor(
            SnailJobProperties properties,
            ObjectProvider<Executor> taskExecutorProvider) {
        if (properties != null && !properties.isManagedPoolEnabled()) {
            return null;
        }
        return taskExecutorProvider != null ? taskExecutorProvider.getIfAvailable() : null;
    }

    /**
     * 将 ateng 统一配置同步填充至 SnailJob 官方原生配置对象
     */
    private void syncNativeProperties(
            SnailJobProperties source,
            com.aizuda.snailjob.client.common.config.SnailJobProperties target,
            Environment environment) {

        // 1. 同步服务端地址解析 (支持逗号分隔集群地址，解析主首选节点)
        String server = source.getServer();
        if (StringUtils.hasText(server)) {
            String primaryNode = server.contains(",") ? server.split(",")[0].trim() : server.trim();
            String[] parts = primaryNode.split(":");
            ServerConfig serverConfig = target.getServer();
            if (serverConfig == null) {
                serverConfig = new ServerConfig();
                target.setServer(serverConfig);
            }
            if (parts.length >= 2) {
                serverConfig.setHost(parts[0].trim());
                try {
                    serverConfig.setPort(Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    // 保底默认端口
                    serverConfig.setPort(17888);
                }
            } else {
                serverConfig.setHost(primaryNode);
            }
        }

        // 2. 同步命名空间与令牌
        if (StringUtils.hasText(source.getNamespace())) {
            target.setNamespace(source.getNamespace());
        }
        if (StringUtils.hasText(source.getToken())) {
            target.setToken(source.getToken());
        }

        // 3. 同步本地客户端端口
        if (source.getPort() != null) {
            target.setPort(source.getPort());
        }

        // 4. 同步应用组名称 (未显式指定 group 则从 spring.application.name 获取)
        String group = source.getGroup();
        if (!StringUtils.hasText(group)) {
            group = environment.getProperty("spring.application.name");
        }
        if (StringUtils.hasText(group)) {
            target.setGroup(group);
        }

        // 5. 同步 OpenAPI 连接配置 (host 留空时默认沿用主 server host)
        if (source.getOpenapi() != null) {
            SnailJobProperties.OpenApiProperties openApi = source.getOpenapi();
            com.aizuda.snailjob.client.common.config.SnailJobProperties.SnailOpenApiConfig nativeOpenApi = target.getOpenapi();
            if (nativeOpenApi == null) {
                nativeOpenApi = new com.aizuda.snailjob.client.common.config.SnailJobProperties.SnailOpenApiConfig();
                target.setOpenapi(nativeOpenApi);
            }
            String resolvedHost = StringUtils.hasText(openApi.getHost())
                    ? openApi.getHost().trim()
                    : (target.getServer() != null ? target.getServer().getHost() : null);
            if (StringUtils.hasText(resolvedHost)) {
                nativeOpenApi.setHost(resolvedHost);
            }
            nativeOpenApi.setPort(openApi.getPort());
            nativeOpenApi.setHttps(openApi.isHttps());
            if (StringUtils.hasText(openApi.getPrefix())) {
                nativeOpenApi.setPrefix(openApi.getPrefix().trim());
            }
        }
    }

    /**
     * SnailJob 属性桥接指示器（封装配置激活与服务端有效性状态）
     */
    public static class SnailJobPropertiesBridge {

        private final SnailJobProperties properties;
        private final boolean serverConfigured;

        public SnailJobPropertiesBridge(SnailJobProperties properties, boolean serverConfigured) {
            this.properties = properties;
            this.serverConfigured = serverConfigured;
        }

        public SnailJobProperties getProperties() {
            return properties;
        }

        public boolean isServerConfigured() {
            return serverConfigured;
        }
    }
}
