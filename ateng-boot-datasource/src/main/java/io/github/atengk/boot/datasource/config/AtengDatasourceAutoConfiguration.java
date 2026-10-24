package io.github.atengk.boot.datasource.config;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.aop.DynamicDataSourceAnnotationAdvisor;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.event.DataSourceInitEvent;
import com.baomidou.dynamic.datasource.processor.DsJakartaHeaderProcessor;
import com.baomidou.dynamic.datasource.processor.DsJakartaSessionProcessor;
import com.baomidou.dynamic.datasource.processor.DsProcessor;
import com.baomidou.dynamic.datasource.processor.DsSpelExpressionProcessor;
import com.baomidou.dynamic.datasource.provider.DynamicDataSourceProvider;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourcePropertiesCustomizer;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.datasource.context.DataSourceAsyncContextSnapshotProvider;
import io.github.atengk.boot.datasource.guard.AtengDynamicDataSourceAnnotationInterceptor;
import io.github.atengk.boot.datasource.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.readwrite.DefaultWriteOccurredResolver;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.readwrite.WriteOccurredResolver;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.security.AtengDataSourceInitEvent;
import io.github.atengk.boot.datasource.security.CryptoUtilPasswordDecryptor;
import io.github.atengk.boot.datasource.security.DataSourcePasswordDecryptor;
import io.github.atengk.boot.datasource.strategy.HealthAwareDynamicDataSourceStrategy;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.util.ClassUtils;

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
        // 自动挂载数据源上下文快照扩展提供者，透传五维异步上下文
        AsyncContext.registerSnapshotProvider(new DataSourceAsyncContextSnapshotProvider());
    }

    /**
     * 注册数据源密码安全解密器 SPI
     *
     * @param datasourceProperties 数据源配置属性
     * @return 密码解密器实例
     */
    @Bean
    @ConditionalOnMissingBean(DataSourcePasswordDecryptor.class)
    public DataSourcePasswordDecryptor dataSourcePasswordDecryptor(DatasourceProperties datasourceProperties) {
        return new CryptoUtilPasswordDecryptor(datasourceProperties);
    }

    /**
     * 注册数据源初始化事件监听与纯内存密码防卫器
     *
     * @param decryptor             密码解密器
     * @param datasourceProperties 数据源配置属性
     * @return 数据源初始化事件处理器实例
     */
    @Bean
    @ConditionalOnMissingBean(DataSourceInitEvent.class)
    public DataSourceInitEvent dataSourceInitEvent(DataSourcePasswordDecryptor decryptor,
                                                   DatasourceProperties datasourceProperties) {
        return new AtengDataSourceInitEvent(decryptor, datasourceProperties);
    }

    /**
     * 注册 Seata 分布式事务透明代理桥接定制器
     *
     * @return 属性定制器实例
     */
    @Bean
    @ConditionalOnClass(name = "io.seata.rm.datasource.DataSourceProxy")
    @ConditionalOnProperty(prefix = "ateng.boot.datasource.seata", name = "enabled", havingValue = "true")
    public DynamicDataSourcePropertiesCustomizer seataDynamicDataSourcePropertiesCustomizer() {
        log.info("检测到 Seata 环境已就绪，启用 Seata 分布式事务透明代理桥接支持");
        return properties -> properties.setSeata(true);
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
     * @param healthAwareStrategy      高可用故障自愈策略
     * @param readAfterWriteManager    写后读主协调管理器
     * @return 受管动态路由数据源实例
     */
    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DynamicRoutingDataSource dataSource(DynamicDataSourceProperties properties,
                                               List<DynamicDataSourceProvider> providers,
                                               DatasourceProperties datasourceProperties,
                                               TenantDataSourceProvider tenantDataSourceProvider,
                                               HealthAwareDynamicDataSourceStrategy healthAwareStrategy,
                                               ReadAfterWriteManager readAfterWriteManager) {
        log.info("装配 AtengDynamicRoutingDataSource 物理多租户受管动态路由数据源");
        AtengDynamicRoutingDataSource dataSource = new AtengDynamicRoutingDataSource(
                providers != null ? providers : Collections.emptyList(),
                datasourceProperties,
                tenantDataSourceProvider,
                healthAwareStrategy,
                readAfterWriteManager
        );
        dataSource.setPrimary(properties.getPrimary());
        dataSource.setStrict(properties.getStrict());
        dataSource.setStrategy(properties.getStrategy() != null ? properties.getStrategy() : HealthAwareDynamicDataSourceStrategy.class);
        dataSource.setP6spy(properties.getP6spy());
        boolean hasSeataClass = ClassUtils.isPresent("io.seata.rm.datasource.DataSourceProxy", null);
        boolean seataEnabled = hasSeataClass && (
                (datasourceProperties.getSeata() != null && datasourceProperties.getSeata().isEnabled())
                        || Boolean.TRUE.equals(properties.getSeata())
        );
        dataSource.setSeata(seataEnabled);
        dataSource.setGraceDestroy(properties.getGraceDestroy());
        return dataSource;
    }

    /**
     * 注册写后读主防延迟写操作感知解析器
     *
     * @return 写操作感知解析器实例
     */
    @Bean
    @ConditionalOnMissingBean(WriteOccurredResolver.class)
    public WriteOccurredResolver defaultWriteOccurredResolver() {
        return new DefaultWriteOccurredResolver();
    }

    /**
     * 注册写后读主防延迟全局协调管理器
     *
     * @param datasourceProperties 数据源配置属性
     * @param writeOccurredResolver 写操作感知解析器
     * @return 防延迟协调管理器实例
     */
    @Bean
    @ConditionalOnMissingBean(ReadAfterWriteManager.class)
    public ReadAfterWriteManager readAfterWriteManager(DatasourceProperties datasourceProperties,
                                                       WriteOccurredResolver writeOccurredResolver) {
        return new ReadAfterWriteManager(datasourceProperties.getReadWriteSplit().getReadAfterWrite(), writeOccurredResolver);
    }

    /**
     * 注册具备从库故障感知与自动黑名单熔断自愈能力的高可用数据源策略
     *
     * @param datasourceProperties 数据源配置属性
     * @return 高可用数据源策略实例
     */
    @Bean
    @ConditionalOnMissingBean(HealthAwareDynamicDataSourceStrategy.class)
    public HealthAwareDynamicDataSourceStrategy healthAwareDynamicDataSourceStrategy(DatasourceProperties datasourceProperties) {
        return new HealthAwareDynamicDataSourceStrategy(datasourceProperties);
    }

    /**
     * 注册编程式切源对称门面与全库批处理容错沙箱
     *
     * @param dataSource               受管动态路由数据源
     * @param datasourceProperties     数据源配置属性
     * @param tenantDataSourceProvider 租户数据源解析器
     * @param guard                    事务切源安全守卫
     * @param creatorProvider          动态数据源创建器 Provider
     * @return 编程式切源门面实例
     */
    @Bean
    @ConditionalOnMissingBean(DynamicDatasourceTemplate.class)
    public DynamicDatasourceTemplate dynamicDatasourceTemplate(DynamicRoutingDataSource dataSource,
                                                               DatasourceProperties datasourceProperties,
                                                               TenantDataSourceProvider tenantDataSourceProvider,
                                                               DynamicDatasourceGuard guard,
                                                               ObjectProvider<DefaultDataSourceCreator> creatorProvider) {
        log.info("装配 DynamicDatasourceTemplate 编程式切源对称门面与批处理沙箱");
        return new DynamicDatasourceTemplate(dataSource, datasourceProperties, tenantDataSourceProvider, guard,
                creatorProvider != null ? creatorProvider.getIfAvailable() : null);
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
