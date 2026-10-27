package io.github.atengk.boot.mybatisplus.autoconfigure;

import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis-Plus 动态查询执行模板自动装配类
 * 声明在 MybatisPlusAutoConfiguration 之后装配，保障在 SqlSessionFactory 初始化完成后装配受管 DynamicQueryTemplate
 *
 * @author Ateng
 * @since 2026-10-28
 */
@AutoConfiguration
@AutoConfigureAfter(name = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@ConditionalOnClass({DynamicQueryTemplate.class, SqlSessionFactory.class})
@ConditionalOnProperty(prefix = "ateng.boot.mybatis-plus.dynamic-query", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengMybatisPlusDynamicQueryAutoConfiguration {

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
}
