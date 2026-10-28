package io.github.atengk.boot.tcp.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式 TCP 监听器组件注解
 * <p>
 * 标注在类上，声明该类包含由 {@link TcpMapping} 标注的 TCP 消息分发方法。
 * 自身挂载 {@link Component}，可由 Spring 容器自动识别并装配。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface TcpListener {

    /**
     * 组件名称或标识
     */
    String value() default "";
}
