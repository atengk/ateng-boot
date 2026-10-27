package io.github.atengk.boot.mybatisplus.autoconfigure;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import io.github.atengk.boot.mybatisplus.handler.DefaultMetaObjectHandler;
import io.github.atengk.boot.mybatisplus.tenant.AtengTenantLineInnerInterceptor;
import io.github.atengk.boot.mybatisplus.tenant.DefaultTenantLineHandler;
import io.github.atengk.boot.core.id.Snowflake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;


import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis-Plus 模块自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("MyBatis-Plus 自动装配测试")
class AtengMybatisPlusAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengMybatisPlusAutoConfiguration.class));

    @Test
    @DisplayName("默认配置下成功装配审计填充器、MybatisPlusInterceptor 插件链与雪花发号器")
    void shouldAutoConfigureDefaultComponents() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DefaultMetaObjectHandler.class);
            assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
            assertThat(context).hasSingleBean(IdentifierGenerator.class);

            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            List<InnerInterceptor> interceptors = interceptor.getInterceptors();
            assertThat(interceptors).hasSize(4);
            assertThat(interceptors.get(0)).isInstanceOf(io.github.atengk.boot.mybatisplus.logicdelete.AtengLogicDeleteInnerInterceptor.class);
            assertThat(interceptors.get(1)).isInstanceOf(PaginationInnerInterceptor.class);
            assertThat(interceptors.get(2)).isInstanceOf(BlockAttackInnerInterceptor.class);
            assertThat(interceptors.get(3)).isInstanceOf(OptimisticLockerInnerInterceptor.class);

            PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) interceptors.get(1);
            assertThat(pagination.getMaxLimit()).isEqualTo(500L);
            assertThat(pagination.isOverflow()).isFalse();

            IdentifierGenerator generator = context.getBean(IdentifierGenerator.class);
            Number id = generator.nextId(new Object());
            assertThat(id).isNotNull();
            assertThat(id.longValue()).isPositive();
            assertThat(generator.nextUUID(new Object())).hasSize(32);
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.mybatis-plus.audit.enabled=false 时禁用审计填充器")
    void shouldDisableAuditHandlerWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.audit.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MetaObjectHandler.class);
                    assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
                });
    }

    @Test
    @DisplayName("配置指定 worker-id 与 datacenter-id 时正确初始化发号器")
    void shouldConfigureSnowflakeGeneratorWithIds() {
        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.snowflake.worker-id=3",
                "ateng.boot.mybatis-plus.snowflake.datacenter-id=7"
        ).run(context -> {
            assertThat(context).hasSingleBean(IdentifierGenerator.class);
            IdentifierGenerator generator = context.getBean(IdentifierGenerator.class);
            Number id = generator.nextId(new Object());
            assertThat(id).isNotNull();
            assertThat(Snowflake.getWorkerId(id.longValue())).isEqualTo(3L);
            assertThat(Snowflake.getDatacenterId(id.longValue())).isEqualTo(7L);
            assertThat(generator.nextUUID(new Object())).hasSize(32);
        });
    }

    @Test
    @DisplayName("支持独立禁用各个拦截器插件")
    void shouldSupportDisablingInterceptors() {
        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.logic-delete.enabled=false",
                "ateng.boot.mybatis-plus.pagination.enabled=false",
                "ateng.boot.mybatis-plus.block-attack.enabled=false",
                "ateng.boot.mybatis-plus.optimistic-locker.enabled=false"
        ).run(context -> {
            assertThat(context).hasSingleBean(MybatisPlusInterceptor.class);
            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            assertThat(interceptor.getInterceptors()).isEmpty();
        });
    }

    @Test
    @DisplayName("支持独立禁用逻辑删除穿透与恢复拦截器插件")
    void shouldSupportDisablingLogicDeleteInterceptor() {
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.logic-delete.enabled=false")
                .run(context -> {
                    MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
                    assertThat(interceptor.getInterceptors())
                            .noneMatch(it -> it instanceof io.github.atengk.boot.mybatisplus.logicdelete.AtengLogicDeleteInnerInterceptor);
                });
    }

    @Test
    @DisplayName("自定义分页属性 maxLimit 与 overflow 正确注入插件")
    void shouldApplyCustomPaginationProperties() {
        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.pagination.max-limit=1000",
                "ateng.boot.mybatis-plus.pagination.overflow=true",
                "ateng.boot.mybatis-plus.pagination.db-type=mysql"
        ).run(context -> {
            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) interceptor.getInterceptors().get(1);
            assertThat(pagination.getMaxLimit()).isEqualTo(1000L);
            assertThat(pagination.isOverflow()).isTrue();
        });
    }

    @Test
    @DisplayName("当容器中存在 CurrentUserProvider 时能够正确装配给审计处理器")
    void shouldInjectCurrentUserProviderIntoAuditHandler() {
        contextRunner.withBean(io.github.atengk.boot.core.spi.CurrentUserProvider.class, () -> () -> "spi_user")
                .run(context -> {
                    assertThat(context).hasSingleBean(DefaultMetaObjectHandler.class);
                });
    }

    @Test
    @DisplayName("支持自动装配注入自定义 secretKey")
    void shouldConfigureSecretKey() {
        String testKey = "TestCustomSecretKey123456789012";
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.crypto.secret-key=" + testKey)
                .run(context -> {
                    assertThat(io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler.getGlobalSecretKey()).isEqualTo(testKey);
                });
    }

    @Test
    @DisplayName("开启行级多租户时成功装配 TenantLineHandler 并在插件链第一位注册 AtengTenantLineInnerInterceptor")
    void shouldConfigureTenantInterceptorsWhenEnabled() {
        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "ateng.boot.mybatis-plus.tenant.column=tenant_num",
                "ateng.boot.mybatis-plus.tenant.ignore-tables=sys_dict,sys_log"
        ).run(context -> {
            assertThat(context).hasSingleBean(TenantLineHandler.class);
            assertThat(context).hasSingleBean(DefaultTenantLineHandler.class);

            TenantLineHandler handler = context.getBean(TenantLineHandler.class);
            assertThat(handler.getTenantIdColumn()).isEqualTo("tenant_num");
            assertThat(handler.ignoreTable("sys_dict")).isTrue();

            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            List<InnerInterceptor> innerInterceptors = interceptor.getInterceptors();
            assertThat(innerInterceptors).hasSize(5);
            assertThat(innerInterceptors.get(0)).isInstanceOf(AtengTenantLineInnerInterceptor.class);
            assertThat(innerInterceptors.get(1)).isInstanceOf(io.github.atengk.boot.mybatisplus.logicdelete.AtengLogicDeleteInnerInterceptor.class);
            assertThat(innerInterceptors.get(2)).isInstanceOf(PaginationInnerInterceptor.class);
            assertThat(innerInterceptors.get(3)).isInstanceOf(BlockAttackInnerInterceptor.class);
            assertThat(innerInterceptors.get(4)).isInstanceOf(OptimisticLockerInnerInterceptor.class);
        });
    }

    @Test
    @DisplayName("当业务已显式注册自定义 TenantLineHandler 时优先使用业务自定义 Bean")
    void shouldPreferCustomTenantLineHandlerBean() {
        TenantLineHandler customHandler = new TenantLineHandler() {
            @Override
            public net.sf.jsqlparser.expression.Expression getTenantId() {
                return new net.sf.jsqlparser.expression.LongValue(9999L);
            }
            @Override
            public String getTenantIdColumn() {
                return "custom_tenant_id";
            }
        };

        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.tenant.enabled=true")
                .withBean(TenantLineHandler.class, () -> customHandler)
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantLineHandler.class);
                    assertThat(context.getBean(TenantLineHandler.class)).isSameAs(customHandler);

                    MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
                    AtengTenantLineInnerInterceptor tenantInterceptor = (AtengTenantLineInnerInterceptor) interceptor.getInterceptors().get(0);
                    assertThat(tenantInterceptor.getTenantLineHandler()).isSameAs(customHandler);
                });
    }

    @Test
    @DisplayName("开启细粒度数据权限时成功装配 DataPermissionHandler 并在插件链中置于分页之前")
    void shouldConfigureDataPermissionInterceptorsWhenEnabled() {
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.data-permission.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.permission.DataPermissionHandler.class);
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.permission.DefaultDataPermissionHandler.class);

                    MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
                    List<InnerInterceptor> innerInterceptors = interceptor.getInterceptors();
                    assertThat(innerInterceptors).hasSize(5);
                    assertThat(innerInterceptors.get(0)).isInstanceOf(io.github.atengk.boot.mybatisplus.permission.AtengDataPermissionInnerInterceptor.class);
                    assertThat(innerInterceptors.get(1)).isInstanceOf(io.github.atengk.boot.mybatisplus.logicdelete.AtengLogicDeleteInnerInterceptor.class);
                    assertThat(innerInterceptors.get(2)).isInstanceOf(PaginationInnerInterceptor.class);
                    assertThat(innerInterceptors.get(3)).isInstanceOf(BlockAttackInnerInterceptor.class);
                    assertThat(innerInterceptors.get(4)).isInstanceOf(OptimisticLockerInnerInterceptor.class);
                });
    }

    @Test
    @DisplayName("多租户与数据权限同时开启时拦截器顺序严格保障：多租户 -> 数据权限 -> 逻辑删除 -> 分页 -> 防全表 -> 乐观锁")
    void shouldMaintainStrictInterceptorOrderWhenBothTenantAndDataPermissionEnabled() {
        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "ateng.boot.mybatis-plus.data-permission.enabled=true"
        ).run(context -> {
            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            List<InnerInterceptor> innerInterceptors = interceptor.getInterceptors();
            assertThat(innerInterceptors).hasSize(6);
            assertThat(innerInterceptors.get(0)).isInstanceOf(AtengTenantLineInnerInterceptor.class);
            assertThat(innerInterceptors.get(1)).isInstanceOf(io.github.atengk.boot.mybatisplus.permission.AtengDataPermissionInnerInterceptor.class);
            assertThat(innerInterceptors.get(2)).isInstanceOf(io.github.atengk.boot.mybatisplus.logicdelete.AtengLogicDeleteInnerInterceptor.class);
            assertThat(innerInterceptors.get(3)).isInstanceOf(PaginationInnerInterceptor.class);
            assertThat(innerInterceptors.get(4)).isInstanceOf(BlockAttackInnerInterceptor.class);
            assertThat(innerInterceptors.get(5)).isInstanceOf(OptimisticLockerInnerInterceptor.class);
        });
    }

    @Test
    @DisplayName("默认自动装配增强型 AtengSqlInjector 实例")
    void shouldAutoConfigureSqlInjector() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(com.baomidou.mybatisplus.core.injector.ISqlInjector.class);
            assertThat(context.getBean(com.baomidou.mybatisplus.core.injector.ISqlInjector.class))
                    .isInstanceOf(io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector.class);
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.mybatis-plus.enabled=false 时全局关闭自动装配")
    void shouldDisableAutoConfigurationWhenEnabledPropertyIsFalse() {
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MybatisPlusInterceptor.class);
                    assertThat(context).doesNotHaveBean(DefaultMetaObjectHandler.class);
                    assertThat(context).doesNotHaveBean(IdentifierGenerator.class);
                    assertThat(context).doesNotHaveBean(com.baomidou.mybatisplus.core.injector.ISqlInjector.class);
                });
    }

    @Test
    @DisplayName("默认情况下动态 SQL 增强组件不装配，保持零侵入零开销")
    void shouldNotConfigureDynamicSqlByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.mybatisplus.dynamicsql.ExternalSqlLoader.class);
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry.class);
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlExecutor.class);
        });
    }

    @Test
    @DisplayName("开启 dynamic-sql.enabled=true 时成功装配 ExternalSqlLoader、DynamicSqlRegistry 与 DynamicSqlExecutor")
    void shouldConfigureDynamicSqlWhenEnabled() {
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory = org.mockito.Mockito.mock(org.apache.ibatis.session.SqlSessionFactory.class);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        javax.sql.DataSource dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource));
        org.mockito.Mockito.when(sqlSessionFactory.getConfiguration()).thenReturn(configuration);

        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.dynamic-sql.enabled=true")
                .withBean(org.apache.ibatis.session.SqlSessionFactory.class, () -> sqlSessionFactory)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.dynamicsql.ExternalSqlLoader.class);
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.dynamicsql.ResourceExternalSqlLoader.class);
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry.class);
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlExecutor.class);
                });
    }

    @Test
    @DisplayName("当业务显式提供自定义 ExternalSqlLoader 时优先使用业务自定义 Bean")
    void shouldPreferCustomExternalSqlLoaderBean() {
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory = org.mockito.Mockito.mock(org.apache.ibatis.session.SqlSessionFactory.class);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        javax.sql.DataSource dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource));
        org.mockito.Mockito.when(sqlSessionFactory.getConfiguration()).thenReturn(configuration);

        io.github.atengk.boot.mybatisplus.dynamicsql.ExternalSqlLoader customLoader = java.util.Collections::emptyList;

        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.dynamic-sql.enabled=true")
                .withBean(org.apache.ibatis.session.SqlSessionFactory.class, () -> sqlSessionFactory)
                .withBean(io.github.atengk.boot.mybatisplus.dynamicsql.ExternalSqlLoader.class, () -> customLoader)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.dynamicsql.ExternalSqlLoader.class);
                    assertThat(context.getBean(io.github.atengk.boot.mybatisplus.dynamicsql.ExternalSqlLoader.class)).isSameAs(customLoader);
                });
    }

    @Test
    @DisplayName("默认配置下成功装配 SlowSqlInterceptor 慢 SQL 审计拦截器")
    void shouldAutoConfigureSlowSqlInterceptorByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor.class);
            io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor interceptor = context.getBean(io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor.class);
            assertThat(interceptor.getThreshold()).isEqualTo(java.time.Duration.ofSeconds(1));
            assertThat(interceptor.isFormatSql()).isTrue();
            assertThat(interceptor.isMaskSensitive()).isTrue();
            assertThat(interceptor.getAlertProviders()).isEmpty();
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.mybatis-plus.slow-sql.enabled=false 时禁用 SlowSqlInterceptor")
    void shouldDisableSlowSqlInterceptorWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.slow-sql.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor.class);
                });
    }

    @Test
    @DisplayName("配置自定义 slow-sql 属性与自定义 SlowSqlAlertProvider 时正确生效")
    void shouldConfigureSlowSqlWithCustomPropertiesAndAlertProvider() {
        io.github.atengk.boot.mybatisplus.slowsql.SlowSqlAlertProvider customAlert = event -> {};

        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.slow-sql.threshold=500ms",
                "ateng.boot.mybatis-plus.slow-sql.format-sql=false",
                "ateng.boot.mybatis-plus.slow-sql.mask-sensitive=false"
        ).withBean(io.github.atengk.boot.mybatisplus.slowsql.SlowSqlAlertProvider.class, () -> customAlert)
        .run(context -> {
            assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor.class);
            io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor interceptor = context.getBean(io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor.class);
            assertThat(interceptor.getThreshold()).isEqualTo(java.time.Duration.ofMillis(500));
            assertThat(interceptor.isFormatSql()).isFalse();
            assertThat(interceptor.isMaskSensitive()).isFalse();
            assertThat(interceptor.getAlertProviders()).containsExactly(customAlert);
            assertThat(interceptor.getAlertProvider()).isSameAs(customAlert);
        });
    }

    @Test
    @DisplayName("当业务显式提供自定义 SlowSqlInterceptor 时优先使用业务自定义 Bean")
    void shouldPreferCustomSlowSqlInterceptorBean() {
        io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor customInterceptor = new io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor();

        contextRunner.withBean(io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor.class, () -> customInterceptor)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor.class);
                    assertThat(context.getBean(io.github.atengk.boot.mybatisplus.slowsql.SlowSqlInterceptor.class)).isSameAs(customInterceptor);
                });
    }

    @Test
    @DisplayName("当环境中存在 PlatformTransactionManager 时成功装配 StreamingQueryTemplate 门面")
    void shouldAutoConfigureStreamingQueryTemplateWhenTransactionManagerPresent() {
        org.springframework.transaction.PlatformTransactionManager mockTm = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);

        contextRunner.withBean(org.springframework.transaction.PlatformTransactionManager.class, () -> mockTm)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate.class);
                    io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate template = context.getBean(io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate.class);
                    assertThat(template.getTransactionTemplate()).isNotNull();
                    assertThat(template.getTransactionTemplate().isReadOnly()).isTrue();
                });
    }

    @Test
    @DisplayName("当业务显式提供自定义 StreamingQueryTemplate 时优先使用业务自定义 Bean")
    void shouldPreferCustomStreamingQueryTemplateBean() {
        org.springframework.transaction.PlatformTransactionManager mockTm = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate customTemplate = new io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate(mockTm);

        contextRunner.withBean(org.springframework.transaction.PlatformTransactionManager.class, () -> mockTm)
                .withBean(io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate.class, () -> customTemplate)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate.class);
                    assertThat(context.getBean(io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate.class)).isSameAs(customTemplate);
                });
    }

    @Test
    @DisplayName("默认配置下成功装配 DynamicDatasourceGuard 多数据源诊断探针与守卫组件")
    void shouldAutoConfigureDynamicDatasourceGuardByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.guard.DynamicDatasourceGuard.class);
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.mybatis-plus.guard.dynamic-datasource=false 时禁用 DynamicDatasourceGuard")
    void shouldDisableDynamicDatasourceGuardWhenConfigured() {
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.guard.dynamic-datasource=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.mybatisplus.guard.DynamicDatasourceGuard.class);
                });
    }

    @Test
    @DisplayName("默认配置下不自动装配 DataChangeAuditInterceptor（默认 false 按需开启）")
    void shouldNotAutoConfigureDataChangeAuditInterceptorByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.mybatisplus.audit.DataChangeAuditInterceptor.class);
        });
    }

    @Test
    @DisplayName("当配置 ateng.boot.mybatis-plus.audit-change.enabled=true 时成功装配 DataChangeAuditInterceptor")
    void shouldAutoConfigureDataChangeAuditInterceptorWhenEnabled() {
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.audit-change.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.audit.DataChangeAuditInterceptor.class);
                });
    }

    @Test
    @DisplayName("当业务显式提供自定义 DataChangeAuditInterceptor 时优先使用业务自定义 Bean")
    void shouldPreferCustomDataChangeAuditInterceptorBean() {
        io.github.atengk.boot.mybatisplus.audit.DataChangeAuditInterceptor customInterceptor =
                new io.github.atengk.boot.mybatisplus.audit.DataChangeAuditInterceptor(
                        new io.github.atengk.boot.mybatisplus.properties.AuditChangeProperties(),
                        null, null, null, "CUSTOM"
                );

        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.audit-change.enabled=true")
                .withBean(io.github.atengk.boot.mybatisplus.audit.DataChangeAuditInterceptor.class, () -> customInterceptor)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.audit.DataChangeAuditInterceptor.class);
                    assertThat(context.getBean(io.github.atengk.boot.mybatisplus.audit.DataChangeAuditInterceptor.class)).isSameAs(customInterceptor);
                });
    }

    @Test
    @DisplayName("当缺少 SqlSessionFactory 时平滑跳过 DynamicQueryTemplate 装配而不会导致启动崩溃")
    void shouldNotAutoConfigureDynamicQueryTemplateWhenSqlSessionFactoryMissing() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate.class);
        });
    }

    @Test
    @DisplayName("当容器中存在 SqlSessionFactory 时默认成功装配 DynamicQueryTemplate")
    void shouldAutoConfigureDynamicQueryTemplateWhenSqlSessionFactoryPresent() {
        org.apache.ibatis.session.SqlSessionFactory mockFactory = org.mockito.Mockito.mock(org.apache.ibatis.session.SqlSessionFactory.class);
        org.apache.ibatis.session.Configuration mockConfig = new org.apache.ibatis.session.Configuration();
        javax.sql.DataSource mockDataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        mockConfig.setEnvironment(new org.apache.ibatis.mapping.Environment("test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), mockDataSource));
        org.mockito.Mockito.when(mockFactory.getConfiguration()).thenReturn(mockConfig);

        contextRunner.withBean(org.apache.ibatis.session.SqlSessionFactory.class, () -> mockFactory)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate.class);
                });
    }

    @Test
    @DisplayName("当配置 ateng.boot.mybatis-plus.dynamic-query.enabled=false 时禁用 DynamicQueryTemplate")
    void shouldDisableDynamicQueryTemplateWhenConfigured() {
        org.apache.ibatis.session.SqlSessionFactory mockFactory = org.mockito.Mockito.mock(org.apache.ibatis.session.SqlSessionFactory.class);
        org.apache.ibatis.session.Configuration mockConfig = new org.apache.ibatis.session.Configuration();
        javax.sql.DataSource mockDataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        mockConfig.setEnvironment(new org.apache.ibatis.mapping.Environment("test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), mockDataSource));
        org.mockito.Mockito.when(mockFactory.getConfiguration()).thenReturn(mockConfig);

        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.dynamic-query.enabled=false")
                .withBean(org.apache.ibatis.session.SqlSessionFactory.class, () -> mockFactory)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate.class);
                });
    }

    @Test
    @DisplayName("当业务显式提供自定义 DynamicQueryTemplate 时优先使用业务自定义 Bean")
    void shouldPreferCustomDynamicQueryTemplateBean() {
        org.apache.ibatis.session.SqlSessionFactory mockFactory = org.mockito.Mockito.mock(org.apache.ibatis.session.SqlSessionFactory.class);
        org.apache.ibatis.session.Configuration mockConfig = new org.apache.ibatis.session.Configuration();
        javax.sql.DataSource mockDataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        mockConfig.setEnvironment(new org.apache.ibatis.mapping.Environment("test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), mockDataSource));
        org.mockito.Mockito.when(mockFactory.getConfiguration()).thenReturn(mockConfig);

        org.mybatis.spring.SqlSessionTemplate mockSession = new org.mybatis.spring.SqlSessionTemplate(mockFactory);
        io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate customTemplate =
                new io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate(mockSession);

        contextRunner.withBean(org.apache.ibatis.session.SqlSessionFactory.class, () -> mockFactory)
                .withBean(io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate.class, () -> customTemplate)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate.class);
                    assertThat(context.getBean(io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate.class)).isSameAs(customTemplate);
                });
    }

    @Test
    @DisplayName("默认配置下不装配 SqlLogInterceptor (enabled=false 防灾默认关闭)")
    void shouldNotAutoConfigureSqlLogInterceptorByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor.class);
        });
    }

    @Test
    @DisplayName("显式配置 ateng.boot.mybatis-plus.sql-log.enabled=true 时成功装配 SqlLogInterceptor")
    void shouldAutoConfigureSqlLogInterceptorWhenEnabled() {
        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.sql-log.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor.class);
                    io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor interceptor =
                            context.getBean(io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor.class);
                    assertThat(interceptor.getProperties().isEnabled()).isTrue();
                    assertThat(interceptor.getProperties().getLevel()).isEqualTo("INFO");
                    assertThat(interceptor.getProperties().isFormatSql()).isTrue();
                    assertThat(interceptor.getProperties().isMaskSensitive()).isTrue();
                    assertThat(interceptor.getProperties().isShowCallLocation()).isTrue();
                    assertThat(interceptor.getProperties().getMaxSqlLength()).isEqualTo(4096);
                });
    }

    @Test
    @DisplayName("当配置自定义 sql-log 属性时正确绑定至 SqlLogInterceptor 实例")
    void shouldBindCustomSqlLogProperties() {
        contextRunner.withPropertyValues(
                "ateng.boot.mybatis-plus.sql-log.enabled=true",
                "ateng.boot.mybatis-plus.sql-log.level=DEBUG",
                "ateng.boot.mybatis-plus.sql-log.format-sql=false",
                "ateng.boot.mybatis-plus.sql-log.mask-sensitive=false",
                "ateng.boot.mybatis-plus.sql-log.show-call-location=false",
                "ateng.boot.mybatis-plus.sql-log.max-sql-length=1024"
        ).run(context -> {
            assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor.class);
            io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor interceptor =
                    context.getBean(io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor.class);
            assertThat(interceptor.getProperties().isEnabled()).isTrue();
            assertThat(interceptor.getProperties().getLevel()).isEqualTo("DEBUG");
            assertThat(interceptor.getProperties().isFormatSql()).isFalse();
            assertThat(interceptor.getProperties().isMaskSensitive()).isFalse();
            assertThat(interceptor.getProperties().isShowCallLocation()).isFalse();
            assertThat(interceptor.getProperties().getMaxSqlLength()).isEqualTo(1024);
        });
    }

    @Test
    @DisplayName("当业务显式提供自定义 SqlLogInterceptor 时优先使用业务自定义 Bean")
    void shouldPreferCustomSqlLogInterceptorBean() {
        io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor customInterceptor =
                new io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor(new io.github.atengk.boot.mybatisplus.properties.SqlLogProperties());

        contextRunner.withPropertyValues("ateng.boot.mybatis-plus.sql-log.enabled=true")
                .withBean(io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor.class, () -> customInterceptor)
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor.class);
                    assertThat(context.getBean(io.github.atengk.boot.mybatisplus.sqllog.SqlLogInterceptor.class)).isSameAs(customInterceptor);
                });
    }
}

