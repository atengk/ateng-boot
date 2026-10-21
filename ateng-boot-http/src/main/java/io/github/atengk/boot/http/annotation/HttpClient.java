package io.github.atengk.boot.http.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 HTTP 客户端核心标记注解
 * 修饰 Spring 6 远程契约接口并显式绑定命名客户端标识与 BaseUrl
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface HttpClient {

    /**
     * 命名客户端标识（{@link #name()} 的别名）
     * 对应配置文件 {@code ateng.boot.http.clients.<name>}
     *
     * @return 命名客户端标识
     */
    @AliasFor("name")
    String value() default "";

    /**
     * 命名客户端标识（{@link #value()} 的别名）
     * 对应配置文件 {@code ateng.boot.http.clients.<name>}
     *
     * @return 命名客户端标识
     */
    @AliasFor("value")
    String name() default "";

    /**
     * 远程服务基础请求地址 (BaseUrl)
     * 支持 {@code ${...}} 占位符解析；配置文件中的 {@code clients.<name>.base-url} 具备更高覆盖优先级
     *
     * @return 基础 URL
     */
    String url() default "";

}
