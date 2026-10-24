package io.github.atengk.boot.datasource.config;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.provider.DynamicDataSourceProvider;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
     * @return 编程式切源门面实例
     */
    @Bean
    @ConditionalOnMissingBean(DynamicDatasourceTemplate.class)
    public DynamicDatasourceTemplate dynamicDatasourceTemplate(DynamicRoutingDataSource dataSource,
                                                               DatasourceProperties datasourceProperties,
                                                               TenantDataSourceProvider tenantDataSourceProvider) {
        log.info("装配 DynamicDatasourceTemplate 编程式切源对称门面与批处理沙箱");
        return new DynamicDatasourceTemplate(dataSource, datasourceProperties, tenantDataSourceProvider);
    }
}
