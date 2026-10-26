package io.github.atengk.boot.tcp.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * TCP 命令字与处理方法映射注解
 * <p>
 * 标注在 {@link TcpListener} 托管类的方法上，声明该方法负责处理特定的 TCP 命令字。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TcpMapping {

    /**
     * 绑定的命令字别名，等同于 {@link #command()}
     */
    @AliasFor("command")
    String value() default "";

    /**
     * 绑定的命令字
     */
    @AliasFor("value")
    String command() default "";
}
