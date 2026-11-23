package io.github.atengk.boot.flyway.autoconfigure;

import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDependsOnDatabaseInitializationDetector;
import org.springframework.util.ClassUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MyBatis / MyBatis-Plus 持久层初始化顺序屏障探测器
 * <p>
 * 补齐 MyBatis-Plus 默认探测器仅覆盖 {@code SqlSessionTemplate} 的短板，
 * 将 {@code SqlSessionFactory}、{@code DdlApplicationRunner}、{@code DynamicSqlRegistry}、
 * {@code DynamicSqlExecutor} 与 {@code DynamicQueryTemplate} 一并纳入数据库初始化依赖图，
 * 确保全部持久层基础设施严格在 Flyway 迁移完成之后初始化。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class AtengMybatisPlusDependsOnDatabaseInitializationDetector
        extends AbstractBeansOfTypeDependsOnDatabaseInitializationDetector {

    private static final String[] DEPENDENT_CLASS_NAMES = {
            "org.apache.ibatis.session.SqlSessionFactory",
            "org.mybatis.spring.SqlSessionTemplate",
            "com.baomidou.mybatisplus.autoconfigure.DdlApplicationRunner",
            "io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry",
            "io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlExecutor",
            "io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate"
    };

    @Override
    protected Set<Class<?>> getDependsOnDatabaseInitializationBeanTypes() {
        ClassLoader classLoader = AtengMybatisPlusDependsOnDatabaseInitializationDetector.class.getClassLoader();
        Set<Class<?>> types = new LinkedHashSet<>();
        for (String className : DEPENDENT_CLASS_NAMES) {
            if (ClassUtils.isPresent(className, classLoader)) {
                try {
                    types.add(ClassUtils.forName(className, classLoader));
                } catch (Throwable ignored) {
                    // 可选持久层类加载异常时不中断其余已就绪类型绑定
                }
            }
        }
        return Collections.unmodifiableSet(types);
    }
}
