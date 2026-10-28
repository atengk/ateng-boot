package io.github.atengk.boot.mybatisplus.dynamicquery.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 实体动态查询敏感防卫注解
 * 声明在持久层实体的属性上，显式禁止外部动态查询条件与排序访问该字段，防御敏感数据探测与未授权查询
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DynamicQueryIgnore {
}
