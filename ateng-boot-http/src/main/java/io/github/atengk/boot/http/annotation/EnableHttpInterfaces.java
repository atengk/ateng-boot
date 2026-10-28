package io.github.atengk.boot.http.annotation;

import io.github.atengk.boot.http.registrar.HttpInterfaceRegistrar;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 HTTP 客户端接口包扫描驱动注解
 * 自动导入 HttpInterfaceRegistrar 扫描候选路径并向 Spring 容器注册代理工厂 Bean
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(HttpInterfaceRegistrar.class)
public @interface EnableHttpInterfaces {

    /**
     * 基础扫描包路径列表（{@link #basePackages()} 的别名）
     *
     * @return 扫描包路径数组
     */
    @AliasFor("basePackages")
    String[] value() default {};

    /**
     * 基础扫描包路径列表（{@link #value()} 的别名）
     *
     * @return 扫描包路径数组
     */
    @AliasFor("value")
    String[] basePackages() default {};

    /**
     * 类型安全的扫描锚点类列表
     * 将扫描这些类所在的包路径
     *
     * @return 锚点类类型数组
     */
    Class<?>[] basePackageClasses() default {};

    /**
     * 显式指定的声明式客户端接口列表
     * 无需全包扫描，直接导入指定的接口定义
     *
     * @return 接口类型数组
     */
    Class<?>[] clients() default {};

}
