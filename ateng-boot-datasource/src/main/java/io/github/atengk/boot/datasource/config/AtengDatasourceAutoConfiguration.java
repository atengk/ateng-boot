package io.github.atengk.boot.datasource.config;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.aop.DynamicDataSourceAnnotationAdvisor;
import com.baomidou.dynamic.datasource.processor.DsJakartaHeaderProcessor;
import com.baomidou.dynamic.datasource.processor.DsJakartaSessionProcessor;
import com.baomidou.dynamic.datasource.processor.DsProcessor;
import com.baomidou.dynamic.datasource.processor.DsSpelExpressionProcessor;
import com.baomidou.dynamic.datasource.provider.DynamicDataSourceProvider;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import io.github.atengk.boot.datasource.guard.AtengDynamicDataSourceAnnotationInterceptor;
import io.github.atengk.boot.datasource.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.expression.BeanFactoryResolver;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;

/**
 * ateng-boot-datasource 核心自动装配入口
 *
 * @author Ateng
 * @since 2026-10-24
 */
@AutoConfiguration(before = DynamicDataSourceAutoConfiguration.class)
@ConditionalOnClass(DynamicRoutingDataSource.class)
@EnableConfigurationProperties({DatasourceProperties.class, DynamicDataSourceProperties.class})
@ConditionalOnProperty(prefix = "ateng.boot.datasource", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengDatasourceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengDatasourceAutoConfiguration.class);

    public AtengDatasourceAutoConfiguration() {
        log.info("初始化 ateng-boot-datasource 动态多数据源与物理隔离底座");
    }

    /**
     * 注册默认租户数据源解析器
     *
     * @param properties 数据源配置属性
     * @return 租户数据源解析器实例
     */
    @Bean
    @ConditionalOnMissingBean(TenantDataSourceProvider.class)
    public TenantDataSourceProvider defaultTenantDataSourceProvider(DatasourceProperties properties) {
        return new DefaultTenantDataSourceProvider(properties);
    }

    /**
     * 注册动态数据源事务切源安全守卫
     *
     * @param datasourceProperties 数据源配置属性
     * @return 事务切源安全守卫实例
     */
    @Bean
    @ConditionalOnMissingBean(DynamicDatasourceGuard.class)
    public DynamicDatasourceGuard dynamicDatasourceGuard(DatasourceProperties datasourceProperties) {
        log.info("装配 DynamicDatasourceGuard 动态数据源事务切源安全守卫");
        return new DynamicDatasourceGuard(datasourceProperties);
    }

    /**
     * 注册具备 SaaS 物理多租户透明切库能力的受管动态路由数据源
     *
     * @param properties               Baomidou 原生多数据源属性
     * @param providers                数据源提供者集合
     * @param datasourceProperties     ateng 多数据源增强配置属性
     * @param tenantDataSourceProvider 租户数据源解析器
     * @return 受管动态路由数据源实例
     */
    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DynamicRoutingDataSource dataSource(DynamicDataSourceProperties properties,
                                               List<DynamicDataSourceProvider> providers,
                                               DatasourceProperties datasourceProperties,
                                               TenantDataSourceProvider tenantDataSourceProvider) {
        log.info("装配 AtengDynamicRoutingDataSource 物理多租户受管动态路由数据源");
        AtengDynamicRoutingDataSource dataSource = new AtengDynamicRoutingDataSource(
                providers != null ? providers : Collections.emptyList(),
                datasourceProperties,
                tenantDataSourceProvider
        );
        dataSource.setPrimary(properties.getPrimary());
        dataSource.setStrict(properties.getStrict());
        dataSource.setStrategy(properties.getStrategy());
        dataSource.setP6spy(properties.getP6spy());
        dataSource.setSeata(properties.getSeata());
        dataSource.setGraceDestroy(properties.getGraceDestroy());
        return dataSource;
    }

    /**
     * 注册编程式切源对称门面与全库批处理容错沙箱
     *
     * @param dataSource               受管动态路由数据源
     * @param datasourceProperties     数据源配置属性
     * @param tenantDataSourceProvider 租户数据源解析器
     * @param guard                    事务切源安全守卫
     * @return 编程式切源门面实例
     */
    @Bean
    @ConditionalOnMissingBean(DynamicDatasourceTemplate.class)
    public DynamicDatasourceTemplate dynamicDatasourceTemplate(DynamicRoutingDataSource dataSource,
                                                               DatasourceProperties datasourceProperties,
                                                               TenantDataSourceProvider tenantDataSourceProvider,
                                                               DynamicDatasourceGuard guard) {
        log.info("装配 DynamicDatasourceTemplate 编程式切源对称门面与批处理沙箱");
        return new DynamicDatasourceTemplate(dataSource, datasourceProperties, tenantDataSourceProvider, guard);
    }

    /**
     * 注册数据源处理器链
     *
     * @param beanFactory                Spring BeanFactory
     * @param dynamicDataSourceProperties Baomidou 配置属性
     * @return 数据源处理器链
     */
    @Bean
    @ConditionalOnMissingBean(DsProcessor.class)
    public DsProcessor dsProcessor(BeanFactory beanFactory, DynamicDataSourceProperties dynamicDataSourceProperties) {
        DsJakartaHeaderProcessor headerProcessor = new DsJakartaHeaderProcessor();
        DsJakartaSessionProcessor sessionProcessor = new DsJakartaSessionProcessor();
        DsSpelExpressionProcessor spelProcessor = new DsSpelExpressionProcessor();
        spelProcessor.setBeanResolver(new BeanFactoryResolver(beanFactory));
        if (dynamicDataSourceProperties.getAop() != null && dynamicDataSourceProperties.getAop().getAllowedSpelTypeAccess() != null) {
            spelProcessor.setAllowedSpelTypeAccess(dynamicDataSourceProperties.getAop().getAllowedSpelTypeAccess());
        }
        headerProcessor.setNextProcessor(sessionProcessor);
        sessionProcessor.setNextProcessor(spelProcessor);
        return headerProcessor;
    }

    /**
     * 注册具备假切源刚性拦截防卫的 @DS 注解切面 Advisor
     *
     * @param dsProcessor                 数据源处理器
     * @param dynamicDataSourceProperties Baomidou 原生多数据源配置属性
     * @param guard                       事务切源安全守卫
     * @return 切源增强 Advisor
     */
    @Bean
    @ConditionalOnMissingBean(name = "dynamicDatasourceAnnotationAdvisor")
    public Advisor dynamicDatasourceAnnotationAdvisor(DsProcessor dsProcessor,
                                                      DynamicDataSourceProperties dynamicDataSourceProperties,
                                                      DynamicDatasourceGuard guard) {
        log.info("装配 AtengDynamicDataSourceAnnotationInterceptor 假切源刚性拦截切面 Advisor");
        Boolean allowedPublicOnly = dynamicDataSourceProperties.getAop() != null
                ? dynamicDataSourceProperties.getAop().getAllowedPublicOnly()
                : false;
        AtengDynamicDataSourceAnnotationInterceptor interceptor = new AtengDynamicDataSourceAnnotationInterceptor(
                allowedPublicOnly,
                dsProcessor,
                guard
        );
        DynamicDataSourceAnnotationAdvisor advisor = new DynamicDataSourceAnnotationAdvisor(interceptor, DS.class);
        if (dynamicDataSourceProperties.getAop() != null && dynamicDataSourceProperties.getAop().getOrder() != null) {
            advisor.setOrder(dynamicDataSourceProperties.getAop().getOrder());
        }
        return advisor;
    }
}
