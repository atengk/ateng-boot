package io.github.atengk.boot.http.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 HTTP 客户端微粒度超时注解
 * 可标注于声明式客户端接口类或接口方法上，动态覆盖全局或客户端级别的建联与读取超时时间。
 * 超时优先级：方法级 @HttpTimeout > 接口级 @HttpTimeout > 客户端配置 (ateng.boot.http.clients.<name>) > 全局配置
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface HttpTimeout {

    /**
     * 读取超时时间（{@link #readTimeout()} 的别名）
     * 支持纯数字毫秒（如 "5000"）、带量纲时间格式（如 "5s", "500ms", "1m" 等）或占位符表达式（如 "${app.timeout:5s}"）
     *
     * @return 读取超时时间配置字符串
     */
    @AliasFor("readTimeout")
    String value() default "";

    /**
     * 读取超时时间（{@link #value()} 的别名）
     * 支持纯数字毫秒（如 "5000"）、带量纲时间格式（如 "5s", "500ms", "1m" 等）或占位符表达式（如 "${app.timeout:5s}"）
     *
     * @return 读取超时时间配置字符串
     */
    @AliasFor("value")
    String readTimeout() default "";

    /**
     * 建联超时时间
     * 支持纯数字毫秒（如 "3000"）、带量纲时间格式（如 "3s", "300ms", "1m" 等）或占位符表达式（如 "${app.connect-timeout:3s}"）
     *
     * @return 建联超时时间配置字符串
     */
    String connectTimeout() default "";

}
