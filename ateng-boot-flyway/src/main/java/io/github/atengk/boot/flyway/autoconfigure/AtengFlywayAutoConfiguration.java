package io.github.atengk.boot.flyway.autoconfigure;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.guard.ProductionSafetyGuard;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.migration.JavaMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;

/**
 * ateng-boot-flyway 自动装配入口
 * <p>
 * 声明排在 Spring Boot 原生 {@link FlywayAutoConfiguration} 之前接管启动迁移入口，
 * 同时透传容器内注册的 {@link FlywayConfigurationCustomizer}、{@link JavaMigration} 与 {@link Callback}。
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
public class AtengFlywayAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengFlywayAutoConfiguration.class);

    public AtengFlywayAutoConfiguration() {
        log.info("初始化 ateng-boot-flyway 数据库版本化迁移底座");
    }

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
     * 注册受管默认主目标 Flyway 实例（同时抑制原生 FlywayAutoConfiguration 重复装配）
     *
     * @param atengProperties      增强配置属性
     * @param flywayProperties     Spring Boot 原生 Flyway 属性
     * @param dataSourceProvider   数据源提供者
     * @param resourceLoader       资源加载器
     * @param customizersProvider  配置定制器提供者
     * @param migrationsProvider   Java 迁移脚本提供者
     * @param callbacksProvider    生命周期回调提供者
     * @param safetyGuard          生产安全守卫
     * @return 受管 Flyway 实例
     */
    @Bean
    @Conditional(FlywayDataSourceAvailableCondition.class)
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
        List<FlywayConfigurationCustomizer> customizers = customizersProvider != null
                ? customizersProvider.orderedStream().toList()
                : Collections.emptyList();
        List<JavaMigration> javaMigrations = migrationsProvider != null
                ? migrationsProvider.orderedStream().toList()
                : Collections.emptyList();
        List<Callback> callbacks = callbacksProvider != null
                ? callbacksProvider.orderedStream().toList()
                : Collections.emptyList();

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
     * 注册对称编程式迁移门面 {@link FlywayTemplate}
     *
     * @param flyway                    受管 Flyway 实例
     * @param atengProperties           增强配置属性
     * @param safetyGuard               生产安全守卫
     * @param migrationStrategyProvider 可选的自定义迁移策略
     * @return 迁移门面实例
     */
    @Bean
    @ConditionalOnBean(Flyway.class)
    @ConditionalOnMissingBean(FlywayTemplate.class)
    public FlywayTemplate flywayTemplate(Flyway flyway,
                                         AtengFlywayProperties atengProperties,
                                         ProductionSafetyGuard safetyGuard,
                                         ObjectProvider<FlywayMigrationStrategy> migrationStrategyProvider) {
        return new FlywayTemplate(
                flyway,
                atengProperties,
                safetyGuard,
                migrationStrategyProvider != null ? migrationStrategyProvider.getIfAvailable() : null
        );
    }

    /**
     * 注册受管启动迁移初始化器，统一按执行模式与安全守卫调度启动迁移
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
}
