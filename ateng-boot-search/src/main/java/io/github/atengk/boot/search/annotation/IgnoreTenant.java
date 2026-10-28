package io.github.atengk.boot.search.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明在搜索实体类或方法上的多租户忽略注解
 * <p>
 * 用于细粒度短路绕过搜索底座自动织入的租户隐式过滤条件（如公共基础字典、系统全局索引）。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface IgnoreTenant {
}
