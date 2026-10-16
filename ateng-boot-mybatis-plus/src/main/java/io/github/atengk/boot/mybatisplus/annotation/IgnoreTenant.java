package io.github.atengk.boot.mybatisplus.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明在 Mapper 接口或 Mapper 方法上的多租户忽略注解
 * 用于细粒度短路绕过 MyBatis-Plus 行级多租户 SQL 拦截改写
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface IgnoreTenant {
}
