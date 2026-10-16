package io.github.atengk.boot.mybatisplus.autoconfigure;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;


/**
 * MyBatis-Plus 增强生态核心自动装配类
 * 按需防御性装配审计填充器、动态拦截器插件链（分页、防全表更新删除、乐观锁）、雪花分布式发号器与透明加解密类型处理器
 *
 * @author Ateng
 * @since 2026-10-15
 */
@AutoConfiguration
@ConditionalOnClass({MybatisPlusInterceptor.class, MetaObjectHandler.class})
@EnableConfigurationProperties(MybatisPlusProperties.class)
public class AtengMybatisPlusAutoConfiguration {

    private final MybatisPlusProperties properties;

    public AtengMybatisPlusAutoConfiguration(MybatisPlusProperties properties) {
        this.properties = properties;
    }

    /**
     * 初始化全局持久层加解密密钥配置
     */
    @PostConstruct
    public void initCrypto() {
        if (properties.getCrypto() != null && properties.getCrypto().getSecretKey() != null) {
            EncryptTypeHandler.setGlobalSecretKey(properties.getCrypto().getSecretKey());
        }
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
        return new DefaultMetaObjectHandler(
                currentUserProvider.getIfAvailable(),
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

        // 3. 装配分页插件
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
     * 注册分布式主键发号器
     *
     * @return 基于 Twitter Snowflake 算法的分布式发号器
     */
    @Bean
    @ConditionalOnMissingBean(IdentifierGenerator.class)
    @ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.snowflake", name = "enabled", havingValue = "true", matchIfMissing = true)
    public IdentifierGenerator identifierGenerator() {
        MybatisPlusProperties.SnowflakeProperties snowflake = properties.getSnowflake();
        if (snowflake.getWorkerId() != null && snowflake.getDatacenterId() != null) {
            return new DefaultIdentifierGenerator(snowflake.getWorkerId(), snowflake.getDatacenterId());
        }
        return new DefaultIdentifierGenerator();
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
}
