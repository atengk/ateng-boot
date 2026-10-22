package io.github.atengk.boot.observability.autoconfigure;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.mybatisplus.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.observability.health.DynamicDatasourceHealthIndicator;
import io.github.atengk.boot.observability.health.RealtimeHealthIndicator;
import io.github.atengk.boot.observability.health.RocketmqHealthIndicator;
import io.github.atengk.boot.observability.health.StorageHealthIndicator;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.realtime.cluster.RealtimeClusterBroadcaster;
import io.github.atengk.boot.realtime.session.RealtimeSessionManager;
import io.github.atengk.boot.rocketmq.listener.RocketmqConsumerContainer;
import io.github.atengk.boot.rocketmq.template.RocketmqTemplate;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 生产级高可用与防假死健康探针自动装配配置
 * <p>
 * 统一纳管 Storage、RocketMQ、Realtime 与动态多数据源专属探针体系，
 * 并提供探针受管异步守护线程池与超时切断防护机制。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@AutoConfiguration
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "ateng.boot.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class AtengHealthAutoConfiguration {

    public static final String HEALTH_PROBE_EXECUTOR_BEAN_NAME = "atengHealthProbeExecutor";

    /**
     * 专属健康探针受管异步守护线程池
     * 限制核心与最大线程数，采用 CallerRunsPolicy 弹性防御，专职执行健康探测，杜绝探针假死阻塞 Actuator 主线程
     */
    @Bean(name = HEALTH_PROBE_EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = HEALTH_PROBE_EXECUTOR_BEAN_NAME)
    public ExecutorService atengHealthProbeExecutor() {
        return new ThreadPoolExecutor(
                2,
                10,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new NamedThreadFactory("ateng-health-probe", true),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * ateng-boot-storage 专属安全健康探针装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StorageDriverFactory.class)
    @ConditionalOnProperty(prefix = "ateng.boot.observability.health.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class StorageHealthIndicatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "storageHealthIndicator")
        public StorageHealthIndicator storageHealthIndicator(
                ObjectProvider<StorageDriverFactory> driverFactoryProvider,
                ObjectProvider<StorageDriver> defaultDriverProvider,
                ObservabilityProperties properties,
                @Qualifier(HEALTH_PROBE_EXECUTOR_BEAN_NAME) ObjectProvider<Executor> executorProvider) {
            return new StorageHealthIndicator(driverFactoryProvider, defaultDriverProvider, properties, executorProvider.getIfAvailable());
        }
    }

    /**
     * ateng-boot-rocketmq 5.x 消息底座专属安全健康探针装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RocketmqTemplate.class)
    @ConditionalOnProperty(prefix = "ateng.boot.observability.health.rocketmq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class RocketmqHealthIndicatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "rocketmqHealthIndicator")
        public RocketmqHealthIndicator rocketmqHealthIndicator(
                ObjectProvider<RocketmqTemplate> templateProvider,
                ObjectProvider<RocketmqConsumerContainer> containerProvider,
                ObservabilityProperties properties,
                @Qualifier(HEALTH_PROBE_EXECUTOR_BEAN_NAME) ObjectProvider<Executor> executorProvider) {
            return new RocketmqHealthIndicator(templateProvider, containerProvider, properties, executorProvider.getIfAvailable());
        }
    }

    /**
     * ateng-boot-realtime 实时双向通信专属安全健康探针装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RealtimeSessionManager.class)
    @ConditionalOnProperty(prefix = "ateng.boot.observability.health.realtime", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class RealtimeHealthIndicatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "realtimeHealthIndicator")
        public RealtimeHealthIndicator realtimeHealthIndicator(
                ObjectProvider<RealtimeSessionManager> sessionManagerProvider,
                ObjectProvider<RealtimeClusterBroadcaster> broadcasterProvider,
                ObservabilityProperties properties,
                @Qualifier(HEALTH_PROBE_EXECUTOR_BEAN_NAME) ObjectProvider<Executor> executorProvider) {
            return new RealtimeHealthIndicator(sessionManagerProvider, broadcasterProvider, properties, executorProvider.getIfAvailable());
        }
    }

    /**
     * 动态多数据源与连接池专属安全健康探针装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({DataSource.class, DynamicDatasourceGuard.class})
    @ConditionalOnProperty(prefix = "ateng.boot.observability.health.dynamic-datasource", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class DynamicDatasourceHealthIndicatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "dynamicDatasourceHealthIndicator")
        public DynamicDatasourceHealthIndicator dynamicDatasourceHealthIndicator(
                ApplicationContext applicationContext,
                ObservabilityProperties properties,
                @Qualifier(HEALTH_PROBE_EXECUTOR_BEAN_NAME) ObjectProvider<Executor> executorProvider) {
            return new DynamicDatasourceHealthIndicator(applicationContext, properties, executorProvider.getIfAvailable());
        }
    }
}
