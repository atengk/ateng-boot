package io.github.atengk.boot.search.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 搜索引擎索引元数据注解
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SearchIndex {

    /**
     * 索引名称或 SpEL 动态表达式（如 "user_idx" 或 "log_#{T(java.time.LocalDate).now().format('yyyyMM')}"）
     */
    String value() default "";

    /**
     * 主分片数（默认 1）
     */
    int shards() default 1;

    /**
     * 副本分片数（默认 1）
     */
    int replicas() default 1;
}
