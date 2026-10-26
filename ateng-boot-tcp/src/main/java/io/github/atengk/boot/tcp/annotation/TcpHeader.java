package io.github.atengk.boot.tcp.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入 TCP 报文头注解
 * <p>
 * 标注在 {@link TcpMapping} 方法的参数上，用于自动从 {@code TcpPacket#getHeaders()} 中提取指定属性值注入。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TcpHeader {

    /**
     * 报文头键名，等同于 {@link #name()}
     */
    @AliasFor("name")
    String value() default "";

    /**
     * 报文头键名
     */
    @AliasFor("value")
    String name() default "";

    /**
     * 是否必填，默认 {@code false}
     */
    boolean required() default false;

    /**
     * 缺省默认值
     */
    String defaultValue() default "";
}
