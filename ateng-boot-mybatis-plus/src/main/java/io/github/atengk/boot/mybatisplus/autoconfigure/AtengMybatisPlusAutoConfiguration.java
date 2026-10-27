package io.github.atengk.boot.mybatisplus.autoconfigure;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import io.github.atengk.boot.core.id.Snowflake;
import io.github.atengk.boot.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.mybatisplus.handler.DefaultMetaObjectHandler;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import io.github.atengk.boot.mybatisplus.permission.AtengDataPermissionInnerInterceptor;
import io.github.atengk.boot.mybatisplus.permission.DataPermissionHandler;
import io.github.atengk.boot.mybatisplus.permission.DefaultDataPermissionHandler;
import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import io.github.atengk.boot.mybatisplus.tenant.AtengTenantLineInnerInterceptor;
import io.github.atengk.boot.mybatisplus.tenant.DefaultTenantLineHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlExecutor;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry;
import io.github.atengk.boot.mybatisplus.dynamicsql.ExternalSqlLoader;
import io.github.atengk.boot.mybatisplus.dynamicsql.ResourceExternalSqlLoader;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.transaction.PlatformTransactionManager;
import io.github.atengk.boot.mybatisplus.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlAlertProvider;
import io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor;
import io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor;
import io.github.atengk.boot.mybatisplus.audit.DataChangeAuditInterceptor;
import io.github.atengk.boot.mybatisplus.audit.DataChangeAuditProvider;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.util.JsonUtil;
import java.util.concurrent.Executor;
import java.util.List;


/**
 * MyBatis-Plus 增强生态核心自动装配类
 * 按需防御性装配审计填充器、动态拦截器插件链（分页、防全表更新删除、乐观锁）、雪花分布式发号器与透明加解密类型处理器
 *
 * @author Ateng
 * @since 2026-10-15
 */
@AutoConfiguration
@AutoConfigureBefore(name = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration")
@ConditionalOnClass({MybatisPlusInterceptor.class, MetaObjectHandler.class})
@ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MybatisPlusProperties.class)
public class AtengMybatisPlusAutoConfiguration {

    private final MybatisPlusProperties properties;

    public AtengMybatisPlusAutoConfiguration(MybatisPlusProperties properties) {
        this.properties = properties;
    }

    /**
     * 初始化全局持久层配置（加解密密钥与动态查询引擎开关）
     */
    @PostConstruct
    public void initGlobalConfig() {
        if (properties.getCrypto() != null && properties.getCrypto().getSecretKey() != null) {
            EncryptTypeHandler.setGlobalSecretKey(properties.getCrypto().getSecretKey());
        }
        if (properties.getDynamicQuery() != null) {
            io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryHelper.setEnabled(
                    properties.getDynamicQuery().isEnabled());
        }
        JacksonTypeHandler.setObjectMapper(JsonUtil.getObjectMapper());
    }

    /**
     * 注册 JacksonTypeHandler 全局 ObjectMapper 绑定生命周期回调
     * 在所有单例 Bean 实例化完成后无感注入 Spring 受管 ObjectMapper，严格集成 JavaTimeModule 与全局脱敏契约
     *
     * @param objectMapperProvider 可选的 Spring 受管 ObjectMapper 提供者
     * @return 智能初始化单例回调
     */
    @Bean
    @ConditionalOnMissingBean(name = "jacksonTypeHandlerCustomizer")
    public SmartInitializingSingleton jacksonTypeHandlerCustomizer(ObjectProvider<ObjectMapper> objectMapperProvider) {
        return () -> {
            ObjectMapper objectMapper = objectMapperProvider.getIfAvailable();
            if (objectMapper != null) {
                JacksonTypeHandler.setObjectMapper(objectMapper);
            } else {
                JacksonTypeHandler.setObjectMapper(JsonUtil.getObjectMapper());
            }
        };
    }

    /**
     * 注册悲观排队锁方言自适应与生命周期探测初始化器
     *
     * @param dataSourceProvider 可选的数据源提供者
     * @return 智能初始化单例回调
     */
    @Bean
    @ConditionalOnMissingBean(name = "pessimisticLockDialectInitializer")
    public SmartInitializingSingleton pessimisticLockDialectInitializer(
            ObjectProvider<javax.sql.DataSource> dataSourceProvider) {
        return () -> {
            if (properties.getPagination() != null && properties.getPagination().getDbType() != null) {
                io.github.atengk.boot.mybatisplus.util.PessimisticLockUtil.setDbType(properties.getPagination().getDbType());
                return;
            }
            javax.sql.DataSource dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource != null) {
                try (java.sql.Connection conn = dataSource.getConnection()) {
                    String jdbcUrl = conn.getMetaData().getURL();
                    com.baomidou.mybatisplus.annotation.DbType dbType = com.baomidou.mybatisplus.extension.toolkit.JdbcUtils.getDbType(jdbcUrl);
                    io.github.atengk.boot.mybatisplus.util.PessimisticLockUtil.setDbType(dbType);
                } catch (Exception ignored) {
                    // 静默兼容，避免连接未准备就绪时崩溃
                }
            }
        };
    }

    /**
     * 注册数据持久层自动审计填充器
     *
     * @param currentUserProvider 可选的当前登录用户提供者 SPI 实现
     * @return 默认元对象审计填充处理器
     */
    @Bean
    @ConditionalOnMissingBean(MetaObjectHandler.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MetaObjectHandler metaObjectHandler(ObjectProvider<CurrentUserProvider> currentUserProvider) {
        CurrentUserProvider provider = currentUserProvider.getIfAvailable();
        if (provider != null) {
            io.github.atengk.boot.mybatisplus.util.BatchUtil.setCurrentUserProvider(provider);
        }
        return new DefaultMetaObjectHandler(
                provider,
                properties.getAudit().getDefaultOperator()
        );
    }

    /**
     * 注册默认行级多租户处理器
     *
     * @return 默认行级多租户处理器实例
     */
    @Bean
    @ConditionalOnMissingBean(TenantLineHandler.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.tenant", name = "enabled", havingValue = "true")
    public TenantLineHandler tenantLineHandler() {
        return new DefaultTenantLineHandler(properties.getTenant());
    }

    /**
     * 注册默认细粒度数据权限处理器
     *
     * @return 默认透传的数据权限处理器实例
     */
    @Bean
    @ConditionalOnMissingBean(DataPermissionHandler.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.data-permission", name = "enabled", havingValue = "true")
    public DataPermissionHandler dataPermissionHandler() {
        return new DefaultDataPermissionHandler();
    }

    /**
     * 注册 MyBatis-Plus 插件拦截器链
     * 顺序装配：多租户拦截器 -> 细粒度数据权限拦截器 -> 分页拦截器 -> 防全表更新/删除拦截器 -> 乐观锁拦截器
     *
     * @param tenantLineHandlerProvider     可选的多租户处理器提供者
     * @param dataPermissionHandlerProvider 可选的数据权限处理器提供者
     * @return 组合插件拦截器
     */
    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            ObjectProvider<TenantLineHandler> tenantLineHandlerProvider,
            ObjectProvider<DataPermissionHandler> dataPermissionHandlerProvider) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 装配多租户插件（必须置于数据权限与分页插件之前，保障全局多租户隔离最外层生效）
        MybatisPlusProperties.TenantProperties tenant = properties.getTenant();
        if (tenant.isEnabled()) {
            TenantLineHandler tenantLineHandler = tenantLineHandlerProvider.getIfAvailable(
                    () -> new DefaultTenantLineHandler(tenant)
            );
            interceptor.addInnerInterceptor(new AtengTenantLineInnerInterceptor(tenantLineHandler));
        }

        // 2. 装配细粒度数据权限插件（置于分页插件之前，保障分页 count SQL 与列表查询受同等权限隔离保护）
        MybatisPlusProperties.DataPermissionProperties dataPermission = properties.getDataPermission();
        if (dataPermission.isEnabled()) {
            DataPermissionHandler dataPermissionHandler = dataPermissionHandlerProvider.getIfAvailable(
                    DefaultDataPermissionHandler::new
            );
            interceptor.addInnerInterceptor(new AtengDataPermissionInnerInterceptor(dataPermissionHandler));
        }

        // 3. 装配逻辑删除动态穿透与误删恢复插件（置于分页插件之前，保障穿透查询的分页 count 与数据列表均基于剥离后的 SQL 统计）
        if (properties.getLogicDelete().isEnabled()) {
            interceptor.addInnerInterceptor(new io.github.atengk.boot.mybatisplus.logicdelete.AtengLogicDeleteInnerInterceptor());
        }

        // 4. 装配分页插件
        MybatisPlusProperties.PaginationProperties pagination = properties.getPagination();
        if (pagination.isEnabled()) {
            PaginationInnerInterceptor paginationInnerInterceptor = pagination.getDbType() != null
                    ? new PaginationInnerInterceptor(pagination.getDbType())
                    : new PaginationInnerInterceptor();
            if (pagination.getMaxLimit() != null) {
                paginationInnerInterceptor.setMaxLimit(pagination.getMaxLimit());
            }
            paginationInnerInterceptor.setOverflow(pagination.isOverflow());
            interceptor.addInnerInterceptor(paginationInnerInterceptor);
        }

        // 4. 装配防全表更新与删除插件
        if (properties.getBlockAttack().isEnabled()) {
            interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        }

        // 5. 装配乐观锁插件
        if (properties.getOptimisticLocker().isEnabled()) {
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        }

        return interceptor;
    }


    /**
     * 注册分布式主键发号器，统一桥接委托至 ateng-boot-core 的 Snowflake 引擎与 IdUtil 静态门面
     *
     * @return 遵循 MyBatis-Plus 契约的分布式发号器
     */
    @Bean
    @ConditionalOnMissingBean(IdentifierGenerator.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.snowflake", name = "enabled", havingValue = "true", matchIfMissing = true)
    public IdentifierGenerator identifierGenerator() {
        MybatisPlusProperties.SnowflakeProperties snowflake = properties.getSnowflake();
        final Snowflake engine;
        if (snowflake.getWorkerId() != null && snowflake.getDatacenterId() != null) {
            engine = new Snowflake(snowflake.getWorkerId(), snowflake.getDatacenterId());
        } else {
            engine = IdUtil.getSnowflake();
        }
        return new IdentifierGenerator() {
            @Override
            public Number nextId(Object entity) {
                return engine.nextId();
            }

            @Override
            public String nextUUID(Object entity) {
                return IdUtil.fastSimpleUUID();
            }
        };
    }

    /**
     * 注册增强型全局 SQL 注入器（自动挂载 insertBatchSomeColumn 原生批量插入并排除逻辑删除字段）
     *
     * @return 增强型 SQL 注入器实例
     */
    @Bean
    @ConditionalOnMissingBean(ISqlInjector.class)
    public ISqlInjector sqlInjector() {
        return new AtengSqlInjector();
    }

    /**
     * 注册外部动态 SQL 资源加载器 SPI 默认实现
     *
     * @param resourceResolver Spring 资源模式解析器
     * @return 基于 Spring Resource 模式的外部 SQL 加载器
     */
    @Bean
    @ConditionalOnMissingBean(ExternalSqlLoader.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.dynamic-sql", name = "enabled", havingValue = "true")
    public ExternalSqlLoader externalSqlLoader(ResourcePatternResolver resourceResolver) {
        return new ResourceExternalSqlLoader(properties.getDynamicSql(), resourceResolver);
    }

    /**
     * 注册外部动态 SQL 注册中心
     *
     * @param configurationProvider 可选的 MyBatis Configuration 提供者
     * @param sqlSessionFactoryProvider 可选的 SqlSessionFactory 提供者
     * @param externalSqlLoader 外部 SQL 加载器
     * @return 动态 SQL 注册中心实例
     */
    @Bean
    @ConditionalOnMissingBean(DynamicSqlRegistry.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.dynamic-sql", name = "enabled", havingValue = "true")
    public DynamicSqlRegistry dynamicSqlRegistry(
            ObjectProvider<Configuration> configurationProvider,
            ObjectProvider<SqlSessionFactory> sqlSessionFactoryProvider,
            ExternalSqlLoader externalSqlLoader) {
        Configuration configuration = configurationProvider.getIfAvailable(() -> {
            SqlSessionFactory sqlSessionFactory = sqlSessionFactoryProvider.getIfAvailable();
            if (sqlSessionFactory != null) {
                return sqlSessionFactory.getConfiguration();
            }
            throw new IllegalStateException("未能找到 Configuration 或 SqlSessionFactory 实例，无法初始化 DynamicSqlRegistry");
        });
        return new DynamicSqlRegistry(configuration, externalSqlLoader, properties.getDynamicSql().isAutoScriptWrap());
    }

    /**
     * 注册通用受管动态查询执行模板组件
     *
     * @param sqlSessionTemplateProvider 可选的 SqlSessionTemplate 提供者
     * @param sqlSessionFactoryProvider 可选的 SqlSessionFactory 提供者
     * @param dynamicSqlRegistryProvider 可选的动态 SQL 注册中心提供者
     * @return 动态查询执行模板实例
     */
    @Bean
    @ConditionalOnMissingBean(DynamicQueryTemplate.class)
    @ConditionalOnBean(SqlSessionFactory.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.dynamic-query", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DynamicQueryTemplate dynamicQueryTemplate(
            ObjectProvider<SqlSessionTemplate> sqlSessionTemplateProvider,
            ObjectProvider<SqlSessionFactory> sqlSessionFactoryProvider,
            ObjectProvider<DynamicSqlRegistry> dynamicSqlRegistryProvider) {
        SqlSessionTemplate sqlSessionTemplate = sqlSessionTemplateProvider.getIfAvailable(() -> {
            SqlSessionFactory sqlSessionFactory = sqlSessionFactoryProvider.getIfAvailable();
            if (sqlSessionFactory != null) {
                return new SqlSessionTemplate(sqlSessionFactory);
            }
            throw new IllegalStateException("未能找到 SqlSessionTemplate 或 SqlSessionFactory 实例，无法初始化 DynamicQueryTemplate");
        });
        return new DynamicQueryTemplate(sqlSessionTemplate, dynamicSqlRegistryProvider.getIfAvailable());
    }

    /**
     * 注册通用编程式动态 SQL 执行器
     *
     * @param sqlSessionTemplateProvider 可选的 SqlSessionTemplate 提供者
     * @param sqlSessionFactoryProvider 可选的 SqlSessionFactory 提供者
     * @param dynamicSqlRegistry 动态 SQL 注册中心
     * @param dynamicQueryTemplateProvider 可选的动态查询执行模板提供者
     * @return 编程式动态 SQL 执行器实例
     */
    @Bean
    @ConditionalOnMissingBean(DynamicSqlExecutor.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.dynamic-sql", name = "enabled", havingValue = "true")
    public DynamicSqlExecutor dynamicSqlExecutor(
            ObjectProvider<SqlSessionTemplate> sqlSessionTemplateProvider,
            ObjectProvider<SqlSessionFactory> sqlSessionFactoryProvider,
            DynamicSqlRegistry dynamicSqlRegistry,
            ObjectProvider<DynamicQueryTemplate> dynamicQueryTemplateProvider) {
        SqlSessionTemplate sqlSessionTemplate = sqlSessionTemplateProvider.getIfAvailable(() -> {
            SqlSessionFactory sqlSessionFactory = sqlSessionFactoryProvider.getIfAvailable();
            if (sqlSessionFactory != null) {
                return new SqlSessionTemplate(sqlSessionFactory);
            }
            throw new IllegalStateException("未能找到 SqlSessionTemplate 或 SqlSessionFactory 实例，无法初始化 DynamicSqlExecutor");
        });
        return new DynamicSqlExecutor(sqlSessionTemplate, dynamicSqlRegistry, dynamicQueryTemplateProvider.getIfAvailable());
    }

    /**
     * 注册动态 SQL 启动全量预热与生命周期初始化器
     *
     * @param dynamicSqlRegistry 动态 SQL 注册中心
     * @return 单例初始化完成后的预热回调
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.dynamic-sql", name = "enabled", havingValue = "true")
    public SmartInitializingSingleton dynamicSqlInitializer(DynamicSqlRegistry dynamicSqlRegistry) {
        return dynamicSqlRegistry::loadAndRegisterAll;
    }

    /**
     * 注册 SQL 日志打印与可执行带参诊断拦截器
     *
     * @return SQL 日志拦截器实例
     */
    @Bean
    @ConditionalOnMissingBean(SqlLogInterceptor.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.sql-log", name = "enabled", havingValue = "true")
    public SqlLogInterceptor sqlLogInterceptor() {
        return new SqlLogInterceptor(properties.getSqlLog());
    }

    /**
     * 注册慢 SQL 性能审计与告警拦截器
     *
     * @param alertProvidersProvider 可选的慢 SQL 告警通知提供者 SPI 实现集合
     * @param eventPublisher Spring 事件发布器
     * @return 慢 SQL 拦截器实例
     */
    @Bean
    @ConditionalOnMissingBean(SlowSqlInterceptor.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.slow-sql", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SlowSqlInterceptor slowSqlInterceptor(
            ObjectProvider<List<SlowSqlAlertProvider>> alertProvidersProvider,
            ApplicationEventPublisher eventPublisher) {
        MybatisPlusProperties.SlowSqlProperties slowSql = properties.getSlowSql();
        List<SlowSqlAlertProvider> providers = alertProvidersProvider.getIfAvailable(List::of);
        return new SlowSqlInterceptor(
                slowSql.getThreshold(),
                slowSql.isFormatSql(),
                slowSql.isMaskSensitive(),
                providers,
                eventPublisher
        );
    }

    /**
     * 注册受管游标流式查询模板与防 OOM 执行门面
     *
     * @param transactionManager 平台事务管理器
     * @return 受管游标流式查询模板实例
     */
    @Bean
    @ConditionalOnMissingBean(StreamingQueryTemplate.class)
    @ConditionalOnBean(PlatformTransactionManager.class)
    public StreamingQueryTemplate streamingQueryTemplate(PlatformTransactionManager transactionManager) {
        return new StreamingQueryTemplate(transactionManager);
    }

    /**
     * 注册多数据源协同诊断探针与上下文守卫组件
     *
     * @param applicationContext Spring 应用上下文
     * @return 多数据源守卫探针实例
     */
    @Bean
    @ConditionalOnMissingBean(DynamicDatasourceGuard.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.guard", name = "dynamic-datasource", havingValue = "true", matchIfMissing = true)
    public DynamicDatasourceGuard dynamicDatasourceGuard(ApplicationContext applicationContext) {
        return new DynamicDatasourceGuard(applicationContext, properties);
    }

    /**
     * 注册数据变更 Diff 审计拦截器
     *
     * @param auditProvider 可选的数据变更审计消费 SPI 提供者
     * @param taskExecutorProvider 可选的受管异步任务执行器提供者
     * @param eventPublisher Spring 事件发布器
     * @param currentUserProvider 可选的当前登录用户提供者
     * @return 数据变更审计拦截器实例
     */
    @Bean
    @ConditionalOnMissingBean(DataChangeAuditInterceptor.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.audit-change", name = "enabled", havingValue = "true")
    public DataChangeAuditInterceptor dataChangeAuditInterceptor(
            ObjectProvider<DataChangeAuditProvider> auditProvider,
            ObjectProvider<Executor> taskExecutorProvider,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<CurrentUserProvider> currentUserProvider) {
        return new DataChangeAuditInterceptor(
                properties.getAuditChange(),
                auditProvider.getIfAvailable(),
                taskExecutorProvider.getIfAvailable(),
                eventPublisher,
                currentUserProvider.getIfAvailable(),
                properties.getAudit().getDefaultOperator()
        );
    }
}
