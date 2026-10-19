package io.github.atengk.boot.mybatisplus.dynamicquery.annotation;

import io.github.atengk.boot.mybatisplus.dynamicquery.QueryOperator;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 动态查询字段映射声明注解
 * 声明在业务特定查询 DTO 字段上，用于显式映射目标持久层实体的属性名与受控操作符
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface QueryField {

    /**
     * 目标实体属性名称（若为空则默认与当前 DTO 字段名保持一致）
     *
     * @return 目标实体属性名称
     */
    String property() default "";

    /**
     * 目标实体数据库物理列名（若显式指定则优先使用列名映射，否则采用 property 属性名）
     *
     * @return 目标数据库物理列名
     */
    String column() default "";

    /**
     * 查询操作符（默认为 EQ 等于）
     *
     * @return 受控操作符
     */
    QueryOperator operator() default QueryOperator.EQ;

    /**
     * 当前 DTO 属性值为空（null、空字符串或空集合）时是否自动忽略（默认 true）
     *
     * @return true 若忽略空值；否则 false
     */
    boolean ignoreNull() default true;
}
