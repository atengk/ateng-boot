package io.github.atengk.boot.flyway.autoconfigure;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.guard.ProductionSafetyGuard;
import io.github.atengk.boot.flyway.listener.FlywayMigrationListener;
import io.github.atengk.boot.flyway.observability.FlywayHealthIndicator;
import io.github.atengk.boot.flyway.observability.FlywayMetricsBinder;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.security.FlywayPasswordDecryptor;
import io.github.atengk.boot.flyway.security.TransientFlywayExecutor;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.migration.JavaMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotatedTypeMetadata;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * ateng-boot-flyway 自动装配入口
 * <p>
 * 声明排在 Spring Boot 原生 {@link FlywayAutoConfiguration} 之前接管启动迁移入口，
 * 透传容器内注册的 {@link FlywayConfigurationCustomizer}、{@link JavaMigration} 与 {@link Callback}，
 * 并统一装配隔离式加密 DDL 凭据、MyBatis-Plus 启动初始化顺序屏障、领域事件总线与可选可观测组件。
 * 当 {@code ateng.boot.flyway.enabled=false} 时退让给 Spring Boot 原生自动装配；
 * 当 {@code spring.flyway.enabled=false} 时联动关闭自动装配。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
@AutoConfiguration(
        before = FlywayAutoConfiguration.class,
        after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class}
)
@ConditionalOnClass(Flyway.class)
@Conditional(AtengFlywayAutoConfiguration.AtengFlywayEnabledCondition.class)
@EnableConfigurationProperties({AtengFlywayProperties.class, FlywayProperties.class})
@Import(DatabaseInitializationDependencyConfigurer.class)
public class AtengFlywayAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengFlywayAutoConfiguration.class);

    public AtengFlywayAutoConfiguration() {
        log.info("初始化 ateng-boot-flyway 数据库版本化迁移底座");
    }

    /**
     * 在启用隔离式 DDL 凭据时清理 Spring Boot 原生遗留 Flyway/Endpoint Bean 定义，防止回退低权限源或探查闭池崩溃
     *
     * @return Bean 定义清理后置处理器
     */
    @Bean
    @ConditionalOnMissingBean(IsolatedFlywayBeanSuppressionPostProcessor.class)
    public static IsolatedFlywayBeanSuppressionPostProcessor atengFlywayIsolatedSuppressionPostProcessor() {
        return new IsolatedFlywayBeanSuppressionPostProcessor();
    }

    /**
     * 核心迁移与门面装配配置（优先于可观测嵌套配置加载，确保 @ConditionalOnBean(FlywayTemplate.class) 准确感知）
     */
    @Configuration(proxyBeanMethods = false)
    static class CoreMigrationConfiguration {

        /**
         * 注册生产防误操作硬守卫
         *
         * @param atengProperties 增强配置属性
         * @param environment     Spring 环境对象
         * @return 生产安全守卫实例
         */
        @Bean
        @ConditionalOnMissingBean(ProductionSafetyGuard.class)
        public ProductionSafetyGuard productionSafetyGuard(AtengFlywayProperties atengProperties,
                                                           Environment environment) {
            return new ProductionSafetyGuard(atengProperties, environment);
        }

        /**
         * 注册基于 Core {@code CryptoUtil} 的 DDL 凭据瞬态解密器
         *
         * @param atengProperties 增强配置属性
         * @return 密码解密器实例
         */
        @Bean
        @ConditionalOnMissingBean(FlywayPasswordDecryptor.class)
        public FlywayPasswordDecryptor flywayPasswordDecryptor(AtengFlywayProperties atengProperties) {
            return new FlywayPasswordDecryptor(atengProperties.getCredential());
        }

        /**
         * 正常模式：注册受管默认主目标 Flyway 实例（启用隔离 DDL 凭据时不注册，防止暴露闭池 Flyway Bean）
         *
         * @param atengProperties     增强配置属性
         * @param flywayProperties    Spring Boot 原生 Flyway 属性
         * @param dataSourceProvider  数据源提供者
         * @param resourceLoader      资源加载器
         * @param customizersProvider 配置定制器提供者
         * @param migrationsProvider  Java 迁移脚本提供者
         * @param callbacksProvider   生命周期回调提供者
         * @param safetyGuard         生产安全守卫
         * @return 受管 Flyway 实例
         */
        @Bean
        @Conditional(NormalFlywayBeanCondition.class)
        @ConditionalOnMissingBean(Flyway.class)
        public Flyway atengFlyway(AtengFlywayProperties atengProperties,
                                  FlywayProperties flywayProperties,
                                  ObjectProvider<DataSource> dataSourceProvider,
                                  ResourceLoader resourceLoader,
                                  ObjectProvider<FlywayConfigurationCustomizer> customizersProvider,
                                  ObjectProvider<JavaMigration> migrationsProvider,
                                  ObjectProvider<Callback> callbacksProvider,
                                  ProductionSafetyGuard safetyGuard) {
            DataSource dataSource = dataSourceProvider.getIfAvailable();
            List<FlywayConfigurationCustomizer> customizers = toOrderedList(customizersProvider);
            List<JavaMigration> javaMigrations = toOrderedList(migrationsProvider);
            List<Callback> callbacks = toOrderedList(callbacksProvider);

            return FlywayConfigurationSupport.buildFlyway(
                    atengProperties.getPrimaryTargetKey(),
                    dataSource,
                    flywayProperties,
                    resourceLoader,
                    customizers,
                    javaMigrations,
                    callbacks,
                    safetyGuard
            );
        }

        /**
         * 隔离式 DDL 凭据模式：注册短生命周期瞬态连接执行器（不向容器注册额外 DataSource Bean）
         *
         * @param atengProperties           增强配置属性
         * @param flywayProperties          Spring Boot 原生 Flyway 属性
         * @param passwordDecryptor         口令瞬态解密器
         * @param resourceLoader            资源加载器
         * @param customizersProvider       配置定制器提供者
         * @param migrationsProvider        Java 迁移脚本提供者
         * @param callbacksProvider         生命周期回调提供者
         * @param safetyGuard               生产安全守卫
         * @param runtimeDataSourceProvider 运行期数据源提供者
         * @return 瞬态 Flyway 执行器
         */
        @Bean
        @Conditional(IsolatedCredentialConfiguredCondition.class)
        @ConditionalOnMissingBean(TransientFlywayExecutor.class)
        public TransientFlywayExecutor transientFlywayExecutor(
                AtengFlywayProperties atengProperties,
                FlywayProperties flywayProperties,
                FlywayPasswordDecryptor passwordDecryptor,
                ResourceLoader resourceLoader,
                ObjectProvider<FlywayConfigurationCustomizer> customizersProvider,
                ObjectProvider<JavaMigration> migrationsProvider,
                ObjectProvider<Callback> callbacksProvider,
                ProductionSafetyGuard safetyGuard,
                ObjectProvider<DataSource> runtimeDataSourceProvider) {
            return new TransientFlywayExecutor(
                    atengProperties,
                    flywayProperties,
                    passwordDecryptor,
                    resourceLoader,
                    toOrderedList(customizersProvider),
                    toOrderedList(migrationsProvider),
                    toOrderedList(callbacksProvider),
                    safetyGuard,
                    runtimeDataSourceProvider
            );
        }

        /**
         * 注册对称编程式迁移门面 {@link FlywayTemplate}（兼容正常模式与隔离 DDL 凭据模式）
         *
         * @param flywayProvider            正常模式 Flyway 提供者
         * @param transientExecutorProvider 隔离模式瞬态执行器提供者
         * @param atengProperties           增强配置属性
         * @param safetyGuard               生产安全守卫
         * @param migrationStrategyProvider 可选的自定义迁移策略
         * @param eventPublisher            Spring 领域事件发布器
         * @param listenersProvider         迁移监听器延迟提供者
         * @return 迁移门面实例
         */
        @Bean
        @Conditional(FlywayTemplateAvailableCondition.class)
        @ConditionalOnMissingBean(FlywayTemplate.class)
        public FlywayTemplate flywayTemplate(ObjectProvider<Flyway> flywayProvider,
                                             ObjectProvider<TransientFlywayExecutor> transientExecutorProvider,
                                             AtengFlywayProperties atengProperties,
                                             ProductionSafetyGuard safetyGuard,
                                             ObjectProvider<FlywayMigrationStrategy> migrationStrategyProvider,
                                             ApplicationEventPublisher eventPublisher,
                                             ObjectProvider<FlywayMigrationListener> listenersProvider) {
            Flyway primaryFlyway = flywayProvider != null ? flywayProvider.getIfAvailable() : null;
            TransientFlywayExecutor transientExecutor = transientExecutorProvider != null
                    ? transientExecutorProvider.getIfAvailable()
                    : null;
            return new FlywayTemplate(
                    primaryFlyway,
                    transientExecutor,
                    atengProperties,
                    safetyGuard,
                    migrationStrategyProvider != null ? migrationStrategyProvider.getIfAvailable() : null,
                    eventPublisher,
                    listenersProvider
            );
        }

        /**
         * 正常模式：注册受管启动迁移初始化器，统一按执行模式与安全守卫调度启动迁移
         *
         * @param flyway                    受管 Flyway 实例
         * @param migrationStrategyProvider 可选迁移策略
         * @param flywayTemplate            迁移门面
         * @param atengProperties           增强配置属性
         * @param flywayProperties          原生 Flyway 属性
         * @param safetyGuard               生产安全守卫
         * @return 受管启动迁移初始化器
         */
        @Bean
        @ConditionalOnBean({Flyway.class, FlywayTemplate.class})
        @ConditionalOnMissingBean(FlywayMigrationInitializer.class)
        public AtengFlywayMigrationInitializer atengFlywayMigrationInitializer(
                Flyway flyway,
                ObjectProvider<FlywayMigrationStrategy> migrationStrategyProvider,
                FlywayTemplate flywayTemplate,
                AtengFlywayProperties atengProperties,
                FlywayProperties flywayProperties,
                ProductionSafetyGuard safetyGuard) {
            return new AtengFlywayMigrationInitializer(
                    flyway,
                    migrationStrategyProvider != null ? migrationStrategyProvider.getIfAvailable() : null,
                    flywayTemplate,
                    atengProperties,
                    flywayProperties,
                    safetyGuard
            );
        }

        /**
         * 隔离模式：注册隔离式 DDL 凭据专属启动初始化器（不暴露 Flyway Bean 且参与初始化依赖屏障）
         *
         * @param flywayTemplate   迁移门面
         * @param atengProperties  增强配置属性
         * @param flywayProperties 原生 Flyway 属性
         * @param safetyGuard      生产安全守卫
         * @return 隔离式启动迁移初始化器
         */
        @Bean
        @ConditionalOnBean({TransientFlywayExecutor.class, FlywayTemplate.class})
        @ConditionalOnMissingBean(AtengFlywayIsolatedInitializer.class)
        public AtengFlywayIsolatedInitializer atengFlywayIsolatedInitializer(
                FlywayTemplate flywayTemplate,
                AtengFlywayProperties atengProperties,
                FlywayProperties flywayProperties,
                ProductionSafetyGuard safetyGuard) {
            return new AtengFlywayIsolatedInitializer(
                    flywayTemplate,
                    atengProperties,
                    flywayProperties,
                    safetyGuard
            );
        }

        private <T> List<T> toOrderedList(ObjectProvider<T> provider) {
            return provider != null ? provider.orderedStream().toList() : Collections.emptyList();
        }
    }

    /**
     * Actuator 健康探针可选扩展装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(prefix = "ateng.boot.flyway.observability", name = "health-enabled", havingValue = "true", matchIfMissing = true)
    static class FlywayHealthObservabilityConfiguration {

        @Bean(name = "flywayHealthIndicator")
        @Conditional(FlywayObservabilityEligibleCondition.class)
        @ConditionalOnMissingBean(value = FlywayHealthIndicator.class, name = "flywayHealthIndicator")
        public FlywayHealthIndicator flywayHealthIndicator(FlywayTemplate flywayTemplate,
                                                           AtengFlywayProperties atengProperties) {
            return new FlywayHealthIndicator(flywayTemplate, atengProperties);
        }
    }

    /**
     * Micrometer 度量指标可选扩展装配
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({MeterRegistry.class, MeterBinder.class})
    @ConditionalOnProperty(prefix = "ateng.boot.flyway.observability", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
    static class FlywayMetricsObservabilityConfiguration {

        @Bean
        @Conditional(FlywayObservabilityEligibleCondition.class)
        @ConditionalOnMissingBean(FlywayMetricsBinder.class)
        public FlywayMetricsBinder flywayMetricsBinder(FlywayTemplate flywayTemplate,
                                                       AtengFlywayProperties atengProperties) {
            return new FlywayMetricsBinder(flywayTemplate, atengProperties);
        }

        @Bean
        @ConditionalOnBean(FlywayMetricsBinder.class)
        @ConditionalOnMissingBean(name = "flywayMetricsBinderInitializer")
        public SmartInitializingSingleton flywayMetricsBinderInitializer(
                FlywayMetricsBinder flywayMetricsBinder,
                ObjectProvider<MeterRegistry> meterRegistryProvider) {
            return () -> {
                if (meterRegistryProvider != null) {
                    meterRegistryProvider.orderedStream().forEach(flywayMetricsBinder::bindTo);
                }
            };
        }
    }

    private static boolean isIsolatedCredentialConfigured(Environment environment) {
        if (environment == null) {
            return false;
        }
        AtengFlywayProperties props = Binder.get(environment)
                .bind("ateng.boot.flyway", AtengFlywayProperties.class)
                .orElse(null);
        return props != null && props.isEnabled()
                && props.getCredential() != null && props.getCredential().isConfigured();
    }

    /**
     * 组合开启条件：必须同时满足 ateng.boot.flyway.enabled 与 spring.flyway.enabled 均未禁用
     */
    static final class AtengFlywayEnabledCondition extends AllNestedConditions {

        AtengFlywayEnabledCondition() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty(prefix = "ateng.boot.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
        static final class AtengEnabled {
        }

        @ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
        static final class SpringFlywayEnabled {
        }
    }

    /**
     * 数据源就绪条件：容器中存在 DataSource Bean 或显式配置了 spring.flyway.url
     */
    static final class FlywayDataSourceAvailableCondition extends AnyNestedCondition {

        FlywayDataSourceAvailableCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnBean(DataSource.class)
        static final class DataSourceBeanAvailable {
        }

        @ConditionalOnProperty(prefix = "spring.flyway", name = "url")
        static final class FlywayUrlConfigured {
        }
    }

    /**
     * 正常模式（非隔离凭据）条件：存在数据源/URL 且未启用隔离 DDL 凭据
     */
    static final class NormalFlywayBeanCondition extends AllNestedConditions {

        NormalFlywayBeanCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @Conditional(FlywayDataSourceAvailableCondition.class)
        static final class DataSourceAvailable {
        }

        @Conditional(IsolatedCredentialNotConfiguredCondition.class)
        static final class NotIsolated {
        }
    }

    /**
     * 门面就绪条件：正常模式 {@link Flyway} Bean 或隔离模式 {@link TransientFlywayExecutor} 至少其一存在
     */
    static final class FlywayTemplateAvailableCondition extends AnyNestedCondition {

        FlywayTemplateAvailableCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnBean(Flyway.class)
        static final class NormalFlywayPresent {
        }

        @ConditionalOnBean(TransientFlywayExecutor.class)
        static final class TransientExecutorPresent {
        }
    }

    /**
     * 可观测扩展生效条件：具备容器 DataSource、独立 spring.flyway.url、隔离 DDL 凭据或自定义 FlywayTemplate
     */
    static final class FlywayObservabilityEligibleCondition extends AnyNestedCondition {

        FlywayObservabilityEligibleCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnBean(DataSource.class)
        static final class DataSourcePresent {
        }

        @ConditionalOnProperty(prefix = "spring.flyway", name = "url")
        static final class FlywayUrlPresent {
        }

        @Conditional(IsolatedCredentialConfiguredCondition.class)
        static final class IsolatedCredentialPresent {
        }

        @ConditionalOnBean(FlywayTemplate.class)
        static final class CustomTemplatePresent {
        }
    }

    /**
     * 匹配已配置隔离 DDL 凭据
     */
    static final class IsolatedCredentialConfiguredCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean configured = isIsolatedCredentialConfigured(context.getEnvironment());
            return configured
                    ? ConditionOutcome.match("已配置 ateng.boot.flyway.credential")
                    : ConditionOutcome.noMatch("未配置隔离式 DDL 凭据");
        }
    }

    /**
     * 匹配未配置隔离 DDL 凭据
     */
    static final class IsolatedCredentialNotConfiguredCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean configured = isIsolatedCredentialConfigured(context.getEnvironment());
            return !configured
                    ? ConditionOutcome.match("未配置隔离式 DDL 凭据")
                    : ConditionOutcome.noMatch("已配置隔离式 DDL 凭据，跳过暴露 Flyway Bean");
        }
    }

    /**
     * 兜底清除原生 FlywayAutoConfiguration / FlywayEndpointAutoConfiguration 注册的 fallback Bean，
     * 兼容测试Runner显式声明原生自动装配类的场景
     */
    static final class IsolatedFlywayBeanSuppressionPostProcessor
            implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

        private static final Set<String> SUPPRESSED_BEAN_NAMES = Set.of(
                "flyway", "flywayInitializer", "flywayConnectionDetails", "flywayEndpoint");
        private static final String BOOT_FLYWAY_PREFIX = "org.springframework.boot.autoconfigure.flyway.";
        private static final String BOOT_ACTUATOR_FLYWAY_PREFIX = "org.springframework.boot.actuate.autoconfigure.flyway.";

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            if (!isIsolatedCredentialConfigured(environment)) {
                return;
            }
            for (String beanName : SUPPRESSED_BEAN_NAMES) {
                if (!registry.containsBeanDefinition(beanName)) {
                    continue;
                }
                BeanDefinition bd = registry.getBeanDefinition(beanName);
                String factoryBeanName = bd.getFactoryBeanName();
                if (factoryBeanName != null && (factoryBeanName.startsWith(BOOT_FLYWAY_PREFIX)
                        || factoryBeanName.startsWith(BOOT_ACTUATOR_FLYWAY_PREFIX))) {
                    registry.removeBeanDefinition(beanName);
                }
            }
        }
    }
}
