package io.github.atengk.boot.satoken.annotation;

import io.github.atengk.boot.satoken.spi.DataOwnerProvider;
import io.github.atengk.boot.satoken.spi.DefaultDataOwnerProvider;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 水平越权防御（IDOR）数据所有权校验注解
 * 声明在 Controller 或 Service 方法上，自动校验方法入参或资源所有者是否为当前登录用户
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CheckDataOwner {

    /**
     * SpEL 表达式提取入参数据所有者标识或资源标识（如 "#dto.userId" 或 "#orderId"）
     *
     * @return SpEL 表达式
     */
    String expr() default "";

    /**
     * 间接资源所有者反查 SPI 提供者实现类（可选，默认为 DefaultDataOwnerProvider）
     *
     * @return 反查提供者类型
     */
    Class<? extends DataOwnerProvider<?>> provider() default DefaultDataOwnerProvider.class;

    /**
     * 是否允许超级管理员特权豁免，默认 true
     *
     * @return true 表示当当前用户具备管理员特权时跳过校验
     */
    boolean allowAdmin() default true;

    /**
     * 越权拦截时的错误提示消息
     *
     * @return 错误提示消息
     */
    String message() default "无权访问该资源";
}
