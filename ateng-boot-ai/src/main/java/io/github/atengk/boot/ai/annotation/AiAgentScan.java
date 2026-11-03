package io.github.atengk.boot.ai.annotation;

import io.github.atengk.boot.ai.registrar.AiAgentRegistrar;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * 智能体（@AiAgent）接口包扫描引导注解
 * 显式指定需要扫描并注册为 Spring Bean 的 @AiAgent 接口所在基础包路径。
 * 若应用未显式声明本注解，框架默认依据 AutoConfigurationPackages 自动探测主程序所在包路径。
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(AiAgentRegistrar.class)
public @interface AiAgentScan {

    /**
     * 待扫描的基础包名（basePackages 的别名）
     *
     * @return 基础包名数组
     */
    @AliasFor("basePackages")
    String[] value() default {};

    /**
     * 待扫描的基础包名
     *
     * @return 基础包名数组
     */
    @AliasFor("value")
    String[] basePackages() default {};

    /**
     * 待扫描的基础类，其所在包将被纳入扫描范围
     *
     * @return 基础类数组
     */
    Class<?>[] basePackageClasses() default {};
}
